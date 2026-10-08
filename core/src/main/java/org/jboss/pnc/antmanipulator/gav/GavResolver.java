package org.jboss.pnc.antmanipulator.gav;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Discovers publishing coordinates (groupId:artifactId:version) for an Ant project by consulting
 * several {@link GavSource}s and aggregating everything they find, with provenance.
 *
 * <p>
 * This is the Ant analogue of PME reading GAV straight off the Maven model — except Ant has no
 * canonical location and a single build can publish many artifacts, so we return a list of
 * {@link ResolvedGav}s (one per finding) rather than a single coordinate. See {@link ResolvedGav}.
 *
 * <p>
 * Sources, in rough order of authority:
 * <ol>
 * <li>{@link PomTemplateSource} — {@code pom.xml}/{@code *.pom} templates (e.g. {@code src/etc/poms}).</li>
 * <li>{@link IvySource} — Apache Ivy {@code ivy.xml} {@code <info>}/{@code <publications>}.</li>
 * <li>{@link PropertySource} — version-bearing properties, resolved via Ant's property engine.</li>
 * <li>{@link ManifestSource} — {@code MANIFEST.MF} / {@code version.txt}.</li>
 * </ol>
 */
public class GavResolver {

    private static final Logger logger = LoggerFactory.getLogger(GavResolver.class);

    /**
     * Directories that never contain source-of-truth coordinates and are expensive to walk.
     * Note: {@code build}/{@code dist} are deliberately NOT ignored — some Ant projects (e.g. JUnit 4)
     * keep hand-written pom templates under {@code build/maven}, so ignoring it hides real coordinates.
     */
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

    /** Property names commonly holding a project version. */
    private static final List<String> VERSION_PROPERTY_NAMES = Arrays.asList(
            "project.version",
            "pom.version",
            "version",
            "project.version.number",
            "release.version",
            "app.version",
            "build.version");

    /**
     * User-supplied {@code -D} keys for injecting coordinates a project never declares (e.g. Xalan's
     * Ant build has a version but no groupId/artifactId anywhere in the tree). Mirrors PME's use of
     * command-line properties to seed/override alignment inputs.
     */
    public static final String ALIGN_GROUP_ID = "alignment.groupId";
    public static final String ALIGN_ARTIFACT_ID = "alignment.artifactId";
    public static final String ALIGN_VERSION = "alignment.version";

    /** A pluggable provider of coordinates from one kind of source. */
    public interface GavSource {
        List<ResolvedGav> resolve(Context ctx);
    }

    /** Everything a source needs: the classified candidate files plus the property engine. */
    public static final class Context {
        final File rootBuildFile;
        final File rootDir;
        final AntPropertyResolver props;
        final List<File> pomTemplates;
        final List<File> maven1Poms;
        final List<File> ivyFiles;
        final List<File> propertyFiles;
        final List<File> manifestFiles;
        final List<File> versionTxtFiles;
        /** User-supplied {@code -D} properties (never {@code null}). */
        final Properties overrides;

        Context(
                File rootBuildFile,
                File rootDir,
                AntPropertyResolver props,
                List<File> pomTemplates,
                List<File> maven1Poms,
                List<File> ivyFiles,
                List<File> propertyFiles,
                List<File> manifestFiles,
                List<File> versionTxtFiles,
                Properties overrides) {
            this.rootBuildFile = rootBuildFile;
            this.rootDir = rootDir;
            this.props = props;
            this.pomTemplates = pomTemplates;
            this.maven1Poms = maven1Poms;
            this.ivyFiles = ivyFiles;
            this.propertyFiles = propertyFiles;
            this.manifestFiles = manifestFiles;
            this.versionTxtFiles = versionTxtFiles;
            this.overrides = overrides;
        }
    }

    private final List<GavSource> sources = Arrays.asList(
            new OverrideSource(),
            new PomTemplateSource(),
            new Maven1ProjectSource(),
            new IvySource(),
            new PropertySource(),
            new ManifestSource());

    /** Resolve with no user-supplied overrides. */
    public List<ResolvedGav> resolve(File rootBuildFile) throws IOException {
        return resolve(rootBuildFile, new Properties());
    }

    /**
     * Resolve all coordinates discoverable from the project rooted at {@code rootBuildFile}, seeded
     * with any user-supplied {@code -Dalignment.*} overrides.
     *
     * @return every finding, sorted most-trustworthy first
     */
    public List<ResolvedGav> resolve(File rootBuildFile, Properties overrides) throws IOException {
        final File root = rootBuildFile.getCanonicalFile();
        final File rootDir = root.getParentFile();

        final Context ctx = buildContext(root, rootDir, overrides == null ? new Properties() : overrides);

        final List<ResolvedGav> all = new ArrayList<>();
        for (GavSource source : sources) {
            try {
                all.addAll(source.resolve(ctx));
            } catch (Exception e) {
                logger.warn("GAV source {} failed: {}", source.getClass().getSimpleName(), e.getMessage());
            }
        }

        all.sort(
                Comparator
                        .comparing(ResolvedGav::getConfidence)
                        .thenComparing(ResolvedGav::coordinate));
        return all;
    }

    private Context buildContext(File root, File rootDir, Properties overrides) throws IOException {
        final List<File> pomTemplates = new ArrayList<>();
        final List<File> maven1Poms = new ArrayList<>();
        final List<File> ivyFiles = new ArrayList<>();
        final List<File> propertyFiles = new ArrayList<>();
        final List<File> manifestFiles = new ArrayList<>();
        final List<File> versionTxtFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(rootDir.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isUnderIgnoredDir(rootDir.toPath(), p))
                    .forEach(p -> {
                        final String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        final File f = p.toFile();
                        if (name.equals("pom.xml") || name.endsWith(".pom") || name.endsWith(".pom.xml")
                                || name.equals("pom.xml.template") || name.equals("pom-template.xml")
                        // Templated pom names, e.g. JUnit's junit-pom-template.xml / junit-dep-pom-template.xml
                                || (name.contains("pom") && name.contains("template") && name.endsWith(".xml"))) {
                            pomTemplates.add(f);
                        } else if (name.equals("project.xml")) {
                            // Legacy Maven-1 project descriptor (e.g. dom4j). Disambiguated from a
                            // Maven-2 POM inside the source, since both use a <project> root element.
                            maven1Poms.add(f);
                        } else if (name.equals("ivy.xml") || (name.startsWith("ivy-") && name.endsWith(".xml"))) {
                            ivyFiles.add(f);
                        } else if (name.endsWith(".properties")) {
                            propertyFiles.add(f);
                        } else if (name.equals("manifest.mf")) {
                            manifestFiles.add(f);
                        } else if (name.equals("version.txt")) {
                            versionTxtFiles.add(f);
                        }
                    });
        }

        final AntPropertyResolver props = AntPropertyResolver.forRoot(root);
        return new Context(
                root,
                rootDir,
                props,
                pomTemplates,
                maven1Poms,
                ivyFiles,
                propertyFiles,
                manifestFiles,
                versionTxtFiles,
                overrides);
    }

    private static boolean isUnderIgnoredDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------------------------------

    /**
     * Emits a coordinate seeded from user-supplied {@code -Dalignment.*} properties. This is how a
     * project that declares no groupId/artifactId anywhere (e.g. Xalan) can still be aligned: the user
     * supplies the GA, and — when {@code alignment.version} is omitted — we attach the version already
     * discovered in the <em>root</em> build file's effective properties (avoiding the noise of unrelated
     * {@code version=} keys scattered through {@code src/}).
     */
    static final class OverrideSource implements GavSource {
        @Override
        public List<ResolvedGav> resolve(Context ctx) {
            String groupId = nullIfEmpty(ctx.overrides.getProperty(ALIGN_GROUP_ID));
            String artifactId = nullIfEmpty(ctx.overrides.getProperty(ALIGN_ARTIFACT_ID));
            if (groupId == null && artifactId == null) {
                return Collections.emptyList(); // nothing supplied
            }

            String version = nullIfEmpty(ctx.overrides.getProperty(ALIGN_VERSION));
            String detail = "user-supplied alignment properties";
            if (version == null) {
                version = versionFromRootProperties(ctx, artifactId);
                if (version != null) {
                    detail += " (version from root build file)";
                }
            }
            return Collections.singletonList(
                    new ResolvedGav(
                            groupId,
                            artifactId,
                            version,
                            ctx.rootBuildFile,
                            detail,
                            SourceKind.OVERRIDE,
                            Confidence.HIGH));
        }

        /**
         * The first version property defined by the root build file, or {@code null}. Tries the
         * {@code <artifactId>.version} convention first (e.g. HSQLDB's {@code hsqldb.version}) — keyed
         * to the supplied artifactId so it stays specific — then the generic well-known names.
         */
        private static String versionFromRootProperties(Context ctx, String artifactId) {
            if (!ctx.props.isFullyLoaded()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            if (artifactId != null) {
                names.add(artifactId + ".version");
                names.add(artifactId + ".version.number");
            }
            names.addAll(VERSION_PROPERTY_NAMES);
            for (String name : names) {
                String value = ctx.props.getProperty(name);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
            return null;
        }
    }

    /** Reads GA(V) from Maven pom templates, applying {@code <parent>} fallback for groupId/version. */
    static final class PomTemplateSource implements GavSource {
        @Override
        public List<ResolvedGav> resolve(Context ctx) {
            final List<ResolvedGav> out = new ArrayList<>();
            for (File pom : ctx.pomTemplates) {
                try {
                    Element project = parseXml(pom).getDocumentElement();
                    if (!"project".equals(project.getLocalName()) && !"project".equals(project.getTagName())) {
                        continue; // not a Maven POM
                    }
                    Element parent = firstChild(project, "parent");
                    String groupId = childText(project, "groupId");
                    String artifactId = childText(project, "artifactId");
                    String version = childText(project, "version");
                    if (groupId == null && parent != null) {
                        groupId = childText(parent, "groupId");
                    }
                    if (version == null && parent != null) {
                        version = childText(parent, "version");
                    }
                    if (artifactId == null) {
                        continue; // a POM with no artifactId is not a coordinate source
                    }
                    out.add(
                            new ResolvedGav(
                                    ctx.props.expand(groupId),
                                    ctx.props.expand(artifactId),
                                    ctx.props.expand(version),
                                    pom,
                                    "pom template",
                                    SourceKind.POM_TEMPLATE,
                                    Confidence.HIGH));
                } catch (Exception e) {
                    logger.debug("Skipping unreadable pom template {}: {}", pom, e.getMessage());
                }
            }
            return out;
        }
    }

    /**
     * Reads GA(V) from a legacy Maven-1 {@code project.xml} (e.g. dom4j). Maven-1 shares the
     * {@code <project>} root element with Maven-2, but names coordinates differently: it uses a single
     * {@code <id>} (serving as both groupId and artifactId when the split {@code <groupId>}/
     * {@code <artifactId>} are absent) and {@code <currentVersion>} for the version. To avoid stealing
     * Maven-2 POMs that happen to be named {@code project.xml}, we only treat a file as Maven-1 when it
     * actually carries a Maven-1 marker ({@code <id>}, {@code <currentVersion>} or {@code <pomVersion>}).
     */
    static final class Maven1ProjectSource implements GavSource {
        @Override
        public List<ResolvedGav> resolve(Context ctx) {
            final List<ResolvedGav> out = new ArrayList<>();
            for (File pom : ctx.maven1Poms) {
                try {
                    Element project = parseXml(pom).getDocumentElement();
                    if (!"project".equals(project.getTagName())) {
                        continue; // not a Maven project descriptor
                    }
                    String id = childText(project, "id");
                    String currentVersion = childText(project, "currentVersion");
                    String pomVersion = childText(project, "pomVersion");
                    if (id == null && currentVersion == null && pomVersion == null) {
                        continue; // no Maven-1 markers — leave it to PomTemplateSource / others
                    }
                    String groupId = childText(project, "groupId");
                    String artifactId = childText(project, "artifactId");
                    // Maven-1's single <id> stands in for whichever of groupId/artifactId is absent.
                    if (groupId == null) {
                        groupId = id;
                    }
                    if (artifactId == null) {
                        artifactId = id;
                    }
                    String version = currentVersion != null ? currentVersion : childText(project, "version");
                    if (artifactId == null) {
                        continue; // no coordinate without at least an artifactId
                    }
                    out.add(
                            new ResolvedGav(
                                    ctx.props.expand(groupId),
                                    ctx.props.expand(artifactId),
                                    ctx.props.expand(version),
                                    pom,
                                    "maven-1 project.xml",
                                    SourceKind.MAVEN1_POM,
                                    Confidence.HIGH));
                } catch (Exception e) {
                    logger.debug("Skipping unreadable maven-1 project.xml {}: {}", pom, e.getMessage());
                }
            }
            return out;
        }
    }

    /** Reads coordinates from Ivy {@code <info>} and each {@code <publications><artifact>}. */
    static final class IvySource implements GavSource {
        @Override
        public List<ResolvedGav> resolve(Context ctx) {
            final List<ResolvedGav> out = new ArrayList<>();
            for (File ivy : ctx.ivyFiles) {
                try {
                    Element module = parseXml(ivy).getDocumentElement();
                    Element info = firstChild(module, "info");
                    if (info == null) {
                        continue;
                    }
                    // Ivy uses '/'-style organisations (e.g. "org/apache"); normalise to Maven dotted form.
                    String org = normaliseOrg(info.getAttribute("organisation"));
                    String rev = ctx.props.expand(nullIfEmpty(info.getAttribute("revision")));
                    String module0 = nullIfEmpty(info.getAttribute("module"));

                    Element publications = firstChild(module, "publications");
                    List<Element> artifacts = publications == null ? Collections.emptyList()
                            : childElements(publications, "artifact");
                    Set<String> emitted = new LinkedHashSet<>();
                    for (Element artifact : artifacts) {
                        String name = nullIfEmpty(artifact.getAttribute("name"));
                        if (name != null && emitted.add(name)) {
                            out.add(
                                    new ResolvedGav(
                                            org,
                                            name,
                                            rev,
                                            ivy,
                                            "ivy publication",
                                            SourceKind.IVY,
                                            Confidence.MEDIUM));
                        }
                    }
                    if (emitted.isEmpty() && module0 != null) {
                        // No <publications>: fall back to the <info> module — but only if this ivy
                        // isn't a pure consumer. An ivy with <dependencies> and no <publications>
                        // (e.g. HSQLDB's build/ivy.xml, which pulls FOP/batik to build the manual) is
                        // resolving dependencies, not declaring the project's own coordinate.
                        Element dependencies = firstChild(module, "dependencies");
                        boolean isConsumer = dependencies != null
                                && !childElements(dependencies, "dependency").isEmpty();
                        if (isConsumer) {
                            logger.debug("Skipping consumer ivy (has <dependencies>, no <publications>): {}", ivy);
                        } else {
                            out.add(
                                    new ResolvedGav(
                                            org,
                                            module0,
                                            rev,
                                            ivy,
                                            "ivy info (no publications)",
                                            SourceKind.IVY,
                                            Confidence.LOW));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Skipping unreadable ivy file {}: {}", ivy, e.getMessage());
                }
            }
            return out;
        }

        private static String normaliseOrg(String org) {
            org = nullIfEmpty(org);
            return org == null ? null : org.replace('/', '.');
        }
    }

    /** Emits version-only findings from Ant properties and {@code *.properties} files. */
    static final class PropertySource implements GavSource {
        @Override
        public List<ResolvedGav> resolve(Context ctx) {
            final List<ResolvedGav> out = new ArrayList<>();

            // Well-known version properties resolved through Ant's property engine.
            if (ctx.props.isFullyLoaded()) {
                for (String name : VERSION_PROPERTY_NAMES) {
                    String value = ctx.props.getProperty(name);
                    if (value != null && !value.trim().isEmpty()) {
                        out.add(
                                new ResolvedGav(
                                        null,
                                        null,
                                        value,
                                        ctx.rootBuildFile,
                                        "property " + name,
                                        SourceKind.PROPERTY,
                                        Confidence.LOW));
                    }
                }
            }

            // Project-version keys in .properties files. We match an exact allowlist rather than any
            // key containing "version": the loose heuristic drags in dependency versions
            // (e.g. junit.version in a libraries.properties) and unrelated keys, which are noise.
            for (File pf : ctx.propertyFiles) {
                try {
                    java.util.Properties props = new java.util.Properties();
                    try (InputStream in = Files.newInputStream(pf.toPath())) {
                        props.load(in);
                    }
                    for (String key : props.stringPropertyNames()) {
                        if (VERSION_PROPERTY_NAMES.contains(key.toLowerCase(Locale.ROOT))) {
                            String value = ctx.props.expand(props.getProperty(key));
                            out.add(
                                    new ResolvedGav(
                                            null,
                                            null,
                                            value,
                                            pf,
                                            "property " + key,
                                            SourceKind.PROPERTY,
                                            Confidence.LOW));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Skipping unreadable properties file {}: {}", pf, e.getMessage());
                }
            }
            return out;
        }
    }

    /** Reads version (and vendor/title hints) from {@code MANIFEST.MF} and {@code version.txt}. */
    static final class ManifestSource implements GavSource {
        @Override
        public List<ResolvedGav> resolve(Context ctx) {
            final List<ResolvedGav> out = new ArrayList<>();

            for (File mf : ctx.manifestFiles) {
                try (InputStream in = Files.newInputStream(mf.toPath())) {
                    Attributes attrs = new Manifest(in).getMainAttributes();
                    String version = ctx.props.expand(attrs.getValue("Implementation-Version"));
                    String groupId = attrs.getValue("Implementation-Vendor-Id");
                    String artifactId = attrs.getValue("Implementation-Title");
                    if (version != null || groupId != null || artifactId != null) {
                        out.add(
                                new ResolvedGav(
                                        ctx.props.expand(groupId),
                                        ctx.props.expand(artifactId),
                                        version,
                                        mf,
                                        "manifest",
                                        SourceKind.MANIFEST,
                                        Confidence.LOW));
                    }
                } catch (Exception e) {
                    logger.debug("Skipping unreadable manifest {}: {}", mf, e.getMessage());
                }
            }

            for (File vt : ctx.versionTxtFiles) {
                try {
                    for (String line : Files.readAllLines(vt.toPath(), StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        String value = eq >= 0 ? line.substring(eq + 1).trim() : line.trim();
                        if (!value.isEmpty()) {
                            out.add(
                                    new ResolvedGav(
                                            null,
                                            null,
                                            ctx.props.expand(value),
                                            vt,
                                            "version.txt",
                                            SourceKind.MANIFEST,
                                            Confidence.LOW));
                            break; // first non-empty value only
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Skipping unreadable version.txt {}: {}", vt, e.getMessage());
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------
    // XML helpers
    // ------------------------------------------------------------------------------------------

    private static Document parseXml(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
        // Replace the default handler (which prints "[Fatal Error]" to stderr) with one that stays quiet
        // and simply lets the exception propagate to our per-file try/catch — some trees contain XML that
        // references external DTDs we intentionally block (e.g. dom4j's docbook docs).
        db.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return db.parse(f);
    }

    private static Element firstChild(Element parent, String tag) {
        List<Element> els = childElements(parent, tag);
        return els.isEmpty() ? null : els.get(0);
    }

    /** Direct child elements with the given tag name (ignores nested descendants). */
    private static List<Element> childElements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(((Element) n).getTagName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** Text of the first direct child element with the given tag, or {@code null}. */
    private static String childText(Element parent, String tag) {
        Element el = firstChild(parent, tag);
        return el == null ? null : nullIfEmpty(el.getTextContent());
    }

    private static String nullIfEmpty(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
