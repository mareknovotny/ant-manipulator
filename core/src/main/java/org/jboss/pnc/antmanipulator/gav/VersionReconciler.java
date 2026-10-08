package org.jboss.pnc.antmanipulator.gav;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Turns tokenised versions into concrete ones by following Ant's two substitution mechanisms:
 *
 * <ol>
 * <li><b>{@code ${property}}</b> — Ant property references, expanded via {@link AntPropertyResolver}.</li>
 * <li><b>{@code @TOKEN@}</b> — Ant <em>filter tokens</em>, substituted at copy time by
 * {@code <filter token="TOKEN" value="..."/>}. The value is itself usually a {@code ${property}},
 * so the two mechanisms chain: {@code @VERSION@} → {@code ${IMPL_VERSION}} → {@code 1.9.1}.</li>
 * </ol>
 *
 * <p>
 * This is the piece Maven never needs (its model is always concrete) but Ant always does — a large
 * fraction of discovered coordinates carry tokens. Resolution is intentionally <b>best-effort</b>:
 *
 * <ul>
 * <li>A token may be defined by several {@code <filter>}s with <em>different</em> values (e.g. Jackson's
 * {@code VERSION} → {@code ${IMPL_VERSION}} in one target and {@code ${IMPL_VERSION}-SNAPSHOT} in
 * another). Every distinct outcome is reported, and {@link Resolution#isAmbiguous()} is set.</li>
 * <li>A token's value may point at a <em>target-scoped</em> {@code <param>}/{@code <property>} rather
 * than a global Ant property (e.g. Tomcat's {@code @MAVEN.DEPLOY.VERSION@} →
 * {@code ${maven.deploy.version}}, set per-target to {@code ${maven.asf.release.deploy.version}}
 * and {@code ...-SNAPSHOT}). We harvest those definitions tree-wide and branch over them too.</li>
 * <li>The chain may bottom out in a value produced only at runtime — e.g. a property an Ant target
 * {@code <echo>}es into a generated file, which no static scan can know. Callers can supply it as a
 * {@code -D} override (the authoritative last mile); otherwise the outcome stays
 * {@link Resolution#isUnresolved() unresolved}, which is the honest answer.</li>
 * </ul>
 */
public final class VersionReconciler {

    private static final Logger logger = LoggerFactory.getLogger(VersionReconciler.class);

    /** {@code @TOKEN@} filter-token references, e.g. {@code @MAVEN.DEPLOY.VERSION@}. */
    private static final Pattern FILTER_TOKEN = Pattern.compile("@([A-Za-z0-9._-]+)@");

    /**
     * {@code ${property}} references left unresolved by {@link AntPropertyResolver}, e.g.
     * {@code ${maven.deploy.version}}.
     */
    private static final Pattern PROP_REF = Pattern.compile("\\$\\{([^}]+)\\}");

    /** Guards against pathological branching when a token has many alternative values. */
    private static final int MAX_CANDIDATES = 64;

    private static final Set<String> IGNORED_DIRS = new LinkedHashSet<>(
            Arrays.asList(
                    "target",
                    "out",
                    "bin",
                    ".git",
                    ".svn",
                    ".idea",
                    ".settings",
                    "node_modules"));

    /** Filter-token name → the distinct raw values assigned to it across the tree (may contain {@code ${}}). */
    private final Map<String, Set<String>> filterTokens;
    /**
     * Property/param name → the distinct values assigned via {@code <param>}/{@code <property>} across the
     * tree. Unlike real Ant properties (which {@link AntPropertyResolver} evaluates), these are often
     * target-scoped and multi-valued — e.g. Tomcat's {@code maven.deploy.version} is set to
     * {@code ${maven.asf.release.deploy.version}} in one target and {@code ...-SNAPSHOT} in another.
     */
    private final Map<String, Set<String>> paramDefs;
    /** User-supplied {@code -D} values that take precedence over discovered definitions for the last mile. */
    private final Map<String, String> overrides;
    private final AntPropertyResolver props;

    private VersionReconciler(
            Map<String, Set<String>> filterTokens,
            Map<String, Set<String>> paramDefs,
            Map<String, String> overrides,
            AntPropertyResolver props) {
        this.filterTokens = filterTokens;
        this.paramDefs = paramDefs;
        this.overrides = overrides;
        this.props = props;
    }

    /**
     * Build a reconciler for the project rooted at {@code rootBuildFile}: collect every
     * {@code <filter token=.. value=..>} defined anywhere under {@code rootDir} (filter definitions
     * routinely live in build files reached only via {@code <subant>} or invoked standalone, so we scan
     * the tree rather than the import graph), and evaluate {@code ${...}} against the root's properties.
     */
    public static VersionReconciler forTree(File rootDir, File rootBuildFile) {
        return forTree(rootDir, rootBuildFile, new Properties());
    }

    /**
     * As {@link #forTree(File, File)} but also seeds the reconciler with user {@code -D} overrides, used
     * as the authoritative value for property references that are only produced at runtime (e.g. Ant
     * targets that {@code <echo>} a generated properties file), which no static scan can otherwise know.
     */
    public static VersionReconciler forTree(File rootDir, File rootBuildFile, Properties overrides) {
        Map<String, Set<String>> tokens = new LinkedHashMap<>();
        Map<String, Set<String>> params = new LinkedHashMap<>();
        collectDefinitions(rootDir, tokens, params);
        Map<String, String> ov = new LinkedHashMap<>();
        if (overrides != null) {
            for (String name : overrides.stringPropertyNames()) {
                ov.put(name, overrides.getProperty(name));
            }
        }
        return new VersionReconciler(tokens, params, ov, AntPropertyResolver.forRoot(rootBuildFile));
    }

    /** The outcome of reconciling one tokenised version string. */
    public static final class Resolution {
        private final String original;
        private final Set<String> resolved; // fully concrete outcomes (no tokens remain)
        private final Set<String> remaining; // outcomes that still carry a token

        Resolution(String original, Set<String> resolved, Set<String> remaining) {
            this.original = original;
            this.resolved = resolved;
            this.remaining = remaining;
        }

        public String getOriginal() {
            return original;
        }

        /** Distinct fully-concrete versions the token could resolve to. */
        public Set<String> getResolved() {
            return resolved;
        }

        /** Outcomes still carrying an unresolved {@code ${...}}/{@code @..@} token. */
        public Set<String> getRemaining() {
            return remaining;
        }

        /** True if at least one concrete value was found and none stayed tokenised. */
        public boolean isResolved() {
            return !resolved.isEmpty() && remaining.isEmpty();
        }

        /** True if more than one distinct concrete value is possible (target-dependent). */
        public boolean isAmbiguous() {
            return resolved.size() > 1;
        }

        /** True if nothing concrete could be derived. */
        public boolean isUnresolved() {
            return resolved.isEmpty();
        }

        /** A one-line, human-readable resolution suitable for logging. */
        public String summarize() {
            if (isUnresolved()) {
                return original + "  ->  (unresolved: " + remaining + ")";
            }
            StringBuilder sb = new StringBuilder(original).append("  ->  ").append(resolved);
            if (isAmbiguous()) {
                sb.append("  [AMBIGUOUS]");
            }
            if (!remaining.isEmpty()) {
                sb.append("  (also unresolved: ").append(remaining).append(")");
            }
            return sb.toString();
        }
    }

    /**
     * Resolve a version string that may contain {@code ${property}} and/or {@code @TOKEN@} references.
     * A non-tokenised string resolves to itself.
     */
    public Resolution reconcile(String version) {
        if (version == null) {
            return new Resolution(null, new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(version);

        // Alternately expand ${...} and substitute one @TOKEN@ at a time, branching over the distinct
        // values a token may have, until nothing changes (or we hit the branching cap).
        for (int iteration = 0; iteration < 16; iteration++) {
            Set<String> next = new LinkedHashSet<>();
            boolean changed = false;
            for (String candidate : candidates) {
                String expanded = props.expand(candidate); // resolve ${...} known to Ant

                // 1) Substitute a @TOKEN@ filter reference, branching over its distinct values.
                Matcher m = FILTER_TOKEN.matcher(expanded);
                if (m.find()) {
                    Set<String> values = filterTokens.get(m.group(1));
                    if (values != null && !values.isEmpty()) {
                        String head = expanded.substring(0, m.start());
                        String tail = expanded.substring(m.end());
                        for (String value : values) {
                            next.add(head + value + tail);
                        }
                        changed = true;
                        continue;
                    }
                }

                // 2) A ${property} Ant couldn't resolve: fall back to a -D override, else to values
                //    discovered in <param>/<property> definitions (target-scoped, possibly multi-valued).
                Set<String> propValues = firstResolvablePropRef(expanded);
                if (propValues != null) {
                    changed = true;
                    next.addAll(propValues);
                    continue;
                }

                next.add(expanded); // no progress possible on this candidate
            }
            if (next.size() > MAX_CANDIDATES) {
                logger.debug(
                        "Reconciliation of '{}' exceeded {} candidates; stopping early.",
                        version,
                        MAX_CANDIDATES);
                candidates = next;
                break;
            }
            candidates = next;
            if (!changed) {
                break;
            }
        }

        Set<String> resolved = new LinkedHashSet<>();
        Set<String> remaining = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (isTokenised(candidate)) {
                remaining.add(candidate);
            } else {
                resolved.add(candidate);
            }
        }
        return new Resolution(version, resolved, remaining);
    }

    private static boolean isTokenised(String s) {
        return s != null && (s.contains("${") || FILTER_TOKEN.matcher(s).find());
    }

    /**
     * Find the first {@code ${X}} in {@code s} for which we have a value (a {@code -D} override, else a
     * discovered {@code <param>}/{@code <property>} definition) and return the string(s) with every
     * occurrence of that {@code ${X}} substituted — one per distinct value, so a target-scoped property
     * with two values yields two branches. Returns {@code null} if no reference is resolvable this way.
     */
    private Set<String> firstResolvablePropRef(String s) {
        Matcher m = PROP_REF.matcher(s);
        while (m.find()) {
            String name = m.group(1);
            String ref = "${" + name + "}";
            String override = overrides.get(name);
            if (override != null) {
                Set<String> out = new LinkedHashSet<>();
                out.add(s.replace(ref, override));
                return out;
            }
            Set<String> values = paramDefs.get(name);
            if (values != null && !values.isEmpty()) {
                Set<String> out = new LinkedHashSet<>();
                for (String value : values) {
                    // Skip a self-reference (e.g. a subant param that just forwards ${X}) to avoid a no-op loop.
                    if (!value.equals(ref)) {
                        out.add(s.replace(ref, value));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Walk the tree once and gather both substitution sources: every {@code <filter token=.. value=..>}
     * (into {@code tokens}) and every {@code <param name=.. value=..>}/{@code <property name=.. value=..>}
     * (into {@code params}). Definitions routinely live in build files reached only via {@code <subant>}
     * or invoked standalone, so we scan the tree rather than the import graph.
     */
    private static void collectDefinitions(
            File rootDir,
            Map<String, Set<String>> tokens,
            Map<String, Set<String>> params) {
        try (Stream<Path> walk = Files.walk(rootDir.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .filter(p -> !isUnderIgnoredDir(rootDir.toPath(), p))
                    .forEach(p -> collectFrom(p.toFile(), tokens, params));
        } catch (Exception e) {
            logger.debug("Failed walking {} for definitions: {}", rootDir, e.getMessage());
        }
    }

    private static void collectFrom(File xml, Map<String, Set<String>> tokens, Map<String, Set<String>> params) {
        try {
            // Cheap text pre-filter so we only DOM-parse files that actually declare something we want.
            String text = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
            boolean hasFilter = text.contains("<filter");
            boolean hasDef = text.contains("<param") || text.contains("<property");
            if (!hasFilter && !hasDef) {
                return;
            }
            Document doc = parseXml(xml);
            if (hasFilter) {
                NodeList filters = doc.getElementsByTagName("filter");
                for (int i = 0; i < filters.getLength(); i++) {
                    Element f = (Element) filters.item(i);
                    String token = f.getAttribute("token");
                    if (!token.isEmpty() && f.hasAttribute("value")) {
                        tokens.computeIfAbsent(token, k -> new LinkedHashSet<>()).add(f.getAttribute("value"));
                    }
                }
            }
            if (hasDef) {
                collectNameValue(doc.getElementsByTagName("param"), params);
                collectNameValue(doc.getElementsByTagName("property"), params);
            }
        } catch (Exception e) {
            logger.debug("Skipping unreadable XML {} while collecting definitions: {}", xml, e.getMessage());
        }
    }

    /** Collect {@code name}/{@code value} pairs from the given elements (ignoring file/location/refid forms). */
    private static void collectNameValue(NodeList elements, Map<String, Set<String>> into) {
        for (int i = 0; i < elements.getLength(); i++) {
            Element e = (Element) elements.item(i);
            String name = e.getAttribute("name");
            if (!name.isEmpty() && e.hasAttribute("value")) {
                into.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(e.getAttribute("value"));
            }
        }
    }

    private static boolean isUnderIgnoredDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Document parseXml(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
        // Replace the default handler (which prints "[Fatal Error]" to stderr) with a quiet one: some
        // trees contain XML referencing external DTDs we intentionally block (e.g. dom4j's docbook docs),
        // and those parse failures are expected — let them propagate to the per-file catch, silently.
        db.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return db.parse(f);
    }
}
