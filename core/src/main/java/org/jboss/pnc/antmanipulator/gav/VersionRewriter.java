package org.jboss.pnc.antmanipulator.gav;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies aligned versions back to an Ant project's source files — the analogue of PME's
 * {@code PomIO.rewritePOMs}. Where PME rewrites the single {@code <version>} in a Maven model, an Ant
 * project's version lives at a <em>definition site</em> behind a token chain (a {@code <property>}, an
 * Ivy {@code revision}, a Maven-1 {@code <currentVersion>}, or a {@code .properties} entry), which is
 * exactly what {@link VersionReconciler} follows <em>forwards</em>; rewriting follows it back.
 *
 * <p>
 * Two design choices keep this safe:
 * <ul>
 * <li><b>Formatting-preserving.</b> Edits are surgical, single-line text replacements of just the
 * version literal — we never re-serialise the XML (which would reflow it). This mirrors PME's goal
 * of a minimal diff, achieved here without a DOM round-trip because Ant declarations are one-per-line.</li>
 * <li><b>Only version-defining positions.</b> A version literal such as {@code 3.8-beta1} also appears
 * in prose (POI's docs), changelogs, etc. We rewrite it <em>only</em> where it is actually defining a
 * version — a {@code value=}/{@code revision=} attribute on a {@code <property>}/{@code <param>}/
 * {@code <filter>}/{@code <info>}, a {@code <currentVersion>}/{@code <version>} element in a Maven-1
 * {@code project.xml}, or the value of a {@code .properties} entry.</li>
 * </ul>
 */
public final class VersionRewriter {

    private static final Logger logger = LoggerFactory.getLogger(VersionRewriter.class);

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

    /** {@code value="..."} on an Ant {@code <property>}/{@code <param>}/{@code <filter>}. */
    private static final Pattern ATTR_VALUE = Pattern.compile("value\\s*=\\s*\"([^\"]*)\"");
    /** Ivy {@code revision="..."} on an {@code <info>}. */
    private static final Pattern IVY_REVISION = Pattern.compile("revision\\s*=\\s*\"([^\"]*)\"");
    /** Maven-1 {@code <currentVersion>..</currentVersion>} / {@code <version>..</version>}. */
    private static final Pattern MAVEN1_ELEMENT = Pattern.compile("<(currentVersion|version)>\\s*([^<]*?)\\s*</\\1>");
    /** Grabs a {@code name="..."} attribute for human-readable context in the preview. */
    private static final Pattern NAME_ATTR = Pattern.compile("name\\s*=\\s*\"([^\"]*)\"");
    /** Start of a version-bearing element, tolerating a line break before its attributes. */
    private static final Pattern START_TAG = Pattern.compile("<(property|param|filter|info)(?=\\s|>|/|$)");

    private VersionRewriter() {
    }

    /** A single planned edit: replace {@code before} with {@code after} on line {@code line} of {@code file}. */
    public static final class Edit {
        private final File file;
        private final int line; // 1-based, for display
        private final String before;
        private final String after;
        private final String oldVersion;
        private final String newVersion;
        private final String context;

        Edit(
                File file,
                int line,
                String before,
                String after,
                String oldVersion,
                String newVersion,
                String context) {
            this.file = file;
            this.line = line;
            this.before = before;
            this.after = after;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.context = context;
        }

        public File getFile() {
            return file;
        }

        public int getLine() {
            return line;
        }

        public String getOldVersion() {
            return oldVersion;
        }

        public String getNewVersion() {
            return newVersion;
        }

        /** A one-line preview suitable for logging. */
        public String summarize() {
            return String.format("%s:%d  %s: %s -> %s", file, line, context, oldVersion, newVersion);
        }
    }

    /** Literal-only convenience overload (no reconciler); see {@link #plan(File, Map, VersionReconciler)}. */
    public static List<Edit> plan(File rootDir, Map<String, String> versionMap) {
        return plan(rootDir, versionMap, null);
    }

    /**
     * Plan (but do not apply) every version rewrite implied by {@code versionMap} across the tree rooted
     * at {@code rootDir}. {@code versionMap} maps a resolved base version (e.g. {@code 3.8-beta1} or
     * {@code 12.0.0-M1}) to the version it should become (e.g. {@code 3.8-beta1-redhat-00001}).
     *
     * <p>
     * A version-defining site is rewritten when its value maps to a target either because it is
     * <b>literally</b> a base version (POI's {@code version.id="3.8-beta1"}) or because it is an
     * <b>expression</b> — {@code ${...}}/{@code @...@} — that {@code reconciler} resolves to exactly one
     * base version (tomcat's {@code version="${version.major}.${version.minor}..."}). In both cases the
     * <em>whole</em> value is replaced with the concrete target version, pinning it the way PME pins a
     * Maven model's {@code <version>}. Expression matching is skipped when {@code reconciler} is null or
     * the expression resolves ambiguously (to more than one target).
     *
     * @return the planned edits, in file/line order (empty if no definition sites were found)
     */
    public static List<Edit> plan(File rootDir, Map<String, String> versionMap, VersionReconciler reconciler) {
        final List<Edit> edits = new ArrayList<>();
        if (versionMap == null || versionMap.isEmpty()) {
            return edits;
        }
        try (Stream<Path> walk = Files.walk(rootDir.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isUnderIgnoredDir(rootDir.toPath(), p))
                    .filter(VersionRewriter::isCandidate)
                    .forEach(p -> planFile(p.toFile(), versionMap, reconciler, edits));
        } catch (Exception e) {
            logger.debug("Failed walking {} for rewrites: {}", rootDir, e.getMessage());
        }
        return edits;
    }

    /**
     * Map a version-defining site's raw value to the version it should become, or {@code null} if it isn't
     * an aligned version. A literal that is itself a base version maps directly; an {@code ${...}}/{@code @...@}
     * expression is reconciled and mapped only if it resolves to exactly one target (unambiguous).
     */
    private static String resolveNewVersion(
            String rawValue,
            Map<String, String> versionMap,
            VersionReconciler reconciler) {
        String v = rawValue.trim();
        String direct = versionMap.get(v);
        if (direct != null) {
            return direct;
        }
        if (reconciler == null || !(v.contains("${") || v.contains("@"))) {
            return null;
        }
        Set<String> targets = new LinkedHashSet<>();
        try {
            for (String resolved : reconciler.reconcile(v).getResolved()) {
                String target = versionMap.get(resolved);
                if (target != null) {
                    targets.add(target);
                }
            }
        } catch (Exception e) {
            return null; // an un-reconcilable value is simply not a rewrite target
        }
        return targets.size() == 1 ? targets.iterator().next() : null; // skip ambiguous
    }

    /**
     * Apply the given edits in place. Edits are matched by their {@code before} line content (not just line
     * number) so a stale plan can never corrupt a file. No backups are written — like PME/GME, this relies on
     * the build files being under version control.
     *
     * @return the files that were modified
     */
    public static List<File> apply(List<Edit> edits) throws IOException {
        // Group edits by file, preserving order.
        Map<File, List<Edit>> byFile = new LinkedHashMap<>();
        for (Edit e : edits) {
            byFile.computeIfAbsent(e.file, k -> new ArrayList<>()).add(e);
        }

        List<File> changed = new ArrayList<>();
        for (Map.Entry<File, List<Edit>> entry : byFile.entrySet()) {
            File file = entry.getKey();
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            // Split keeping empty trailing fields so we can rejoin without dropping a final newline; any
            // "\r" of a CRLF file stays attached to each line and is preserved on rejoin.
            String[] lines = text.split("\n", -1);
            boolean fileChanged = false;

            for (Edit e : entry.getValue()) {
                boolean applied = false;
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].equals(e.before)) {
                        lines[i] = e.after;
                        applied = true;
                        fileChanged = true;
                        break;
                    }
                }
                if (!applied) {
                    logger.warn("Skipping stale edit (line no longer matches) in {}: {}", file, e.before.trim());
                }
            }

            if (fileChanged) {
                Files.write(file.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                changed.add(file);
            }
        }
        return changed;
    }

    private static boolean isCandidate(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") || name.endsWith(".properties") || name.endsWith(".pom");
    }

    private static void planFile(
            File file,
            Map<String, String> versionMap,
            VersionReconciler reconciler,
            List<Edit> edits) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            // Cheap pre-filter: a file is relevant if it mentions a base version literally, or (when we can
            // reconcile expressions) if it contains an ${...}/@...@ expression that might resolve to one.
            boolean relevant = reconciler != null && (text.contains("${") || text.contains("@"));
            if (!relevant) {
                for (String base : versionMap.keySet()) {
                    if (text.contains(base)) {
                        relevant = true;
                        break;
                    }
                }
            }
            if (!relevant) {
                return;
            }

            boolean isProperties = file.getName().toLowerCase(Locale.ROOT).endsWith(".properties");
            boolean isMaven1 = file.getName().equalsIgnoreCase("project.xml");

            String[] lines = text.split("\n", -1);
            if (isProperties) {
                for (int i = 0; i < lines.length; i++) {
                    Edit edit = planPropertiesLine(file, i + 1, lines[i], versionMap, reconciler);
                    if (edit != null) {
                        edits.add(edit);
                    }
                }
            } else {
                planXml(file, lines, versionMap, reconciler, isMaven1, edits);
            }
        } catch (Exception e) {
            logger.debug("Skipping unreadable file {} while planning rewrites: {}", file, e.getMessage());
        }
    }

    /**
     * Element-aware XML scan. Ant declarations are usually one-per-line, but an attribute can be split
     * across lines (tomcat's {@code <param name="maven.deploy.version"\n value="${...}"/>}). We therefore
     * track the currently-open {@code <property>}/{@code <param>}/{@code <filter>}/{@code <info>} element so
     * a {@code value=}/{@code revision=} on a continuation line is still recognised and rewritten in place.
     */
    private static void planXml(
            File file,
            String[] lines,
            Map<String, String> versionMap,
            VersionReconciler reconciler,
            boolean isMaven1,
            List<Edit> edits) {
        String openTag = null; // property|param|filter|info whose start tag we've seen but not yet its version attr
        String openName = null; // its name= (from the start-tag line), for context
        boolean emitted = false; // already produced an edit for the open element

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Maven-1 <currentVersion>/<version> element (single line, independent of attribute elements).
            Matcher me = MAVEN1_ELEMENT.matcher(line);
            if (me.find()) {
                String tag = me.group(1);
                String v = me.group(2);
                String next = resolveNewVersion(v, versionMap, reconciler);
                if (next != null && !next.equals(v) && ("currentVersion".equals(tag) || isMaven1)) {
                    String after = line.substring(0, me.start(2)) + next + line.substring(me.end(2));
                    edits.add(new Edit(file, i + 1, line, after, v, next, "maven-1 <" + tag + ">"));
                    continue;
                }
            }

            if (openTag == null) {
                Matcher st = START_TAG.matcher(line);
                if (!st.find()) {
                    continue;
                }
                openTag = st.group(1);
                Matcher nm = NAME_ATTR.matcher(line);
                openName = nm.find(st.end()) ? nm.group(1) : null;
                emitted = false;
            }

            if (!emitted) {
                Edit e = tryAttr(file, i + 1, line, openTag, openName, versionMap, reconciler);
                if (e != null) {
                    edits.add(e);
                    emitted = true;
                }
            }
            if (line.indexOf('>') >= 0) { // start tag closed (either `>` or `/>`)
                openTag = null;
                openName = null;
                emitted = false;
            }
        }
    }

    /** Match a version attribute ({@code revision=} for {@code <info>}, else {@code value=}) on one line. */
    private static Edit tryAttr(
            File file,
            int lineNo,
            String line,
            String tag,
            String name,
            Map<String, String> versionMap,
            VersionReconciler reconciler) {
        Matcher m = ("info".equals(tag) ? IVY_REVISION : ATTR_VALUE).matcher(line);
        while (m.find()) {
            String v = m.group(1);
            String next = resolveNewVersion(v, versionMap, reconciler);
            if (next != null && !next.equals(v)) {
                String after = line.substring(0, m.start(1)) + next + line.substring(m.end(1));
                String context = "info".equals(tag) ? "ivy revision"
                        : (name != null ? "property " + name : tag);
                return new Edit(file, lineNo, line, after, v, next, context);
            }
        }
        return null;
    }

    private static Edit planPropertiesLine(
            File file,
            int lineNo,
            String line,
            Map<String, String> versionMap,
            VersionReconciler reconciler) {
        String stripped = line.trim();
        if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
            return null; // comment or blank
        }
        int sep = firstUnescapedSeparator(line);
        if (sep < 0) {
            return null;
        }
        String rhs = line.substring(sep + 1);
        String v = rhs.trim();
        String next = resolveNewVersion(v, versionMap, reconciler);
        if (next != null && !next.equals(v)) {
            int valueStart = line.indexOf(v, sep + 1);
            String after = line.substring(0, valueStart) + next + line.substring(valueStart + v.length());
            return new Edit(file, lineNo, line, after, v, next, "property " + line.substring(0, sep).trim());
        }
        return null;
    }

    /** Index of the first {@code =} or {@code :} that separates a properties key from its value, or -1. */
    private static int firstUnescapedSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
            } else if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isUnderIgnoredDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
