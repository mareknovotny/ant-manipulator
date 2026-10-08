package org.jboss.pnc.antmanipulator.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lightweight, raw peek at a single Ant build file. This is the Ant analogue of
 * pom-manipulation-ext's {@code PomPeek}: it reads just enough via a plain XML parse to discover how
 * this file links to <em>other</em> build files, without executing any tasks or resolving the full
 * effective model.
 *
 * <p>
 * Where a Maven POM links to other POMs through {@code <parent>} and {@code <modules>}, an Ant
 * build file links through the {@code <import>}, {@code <include>}, {@code <ant>} and {@code <subant>}
 * tasks. Those references are collected here as {@link Reference}s and followed by
 * {@link BuildFileScanner}.
 *
 * <p>
 * A raw parse is used (rather than Ant's {@code ProjectHelper}) for two reasons that mirror PME's
 * design: (1) {@code ProjectHelper} executes top-level tasks at parse time, which can fail on missing
 * antlibs; and (2) discovery must be cheap and side-effect free. The richer effective model is read
 * separately by {@link BuildFilePeek} once the hierarchy is known.
 */
public class RawBuildFilePeek {

    /** The kind of inter-build-file link. */
    public enum Kind {
        IMPORT, INCLUDE, ANT, SUBANT
    }

    /** A single reference from this build file to another build file. */
    public static final class Reference {
        private final Kind kind;
        private final String rawPath;
        private final File resolved; // null when the path could not be resolved statically (e.g. ${...})

        Reference(Kind kind, String rawPath, File resolved) {
            this.kind = kind;
            this.rawPath = rawPath;
            this.resolved = resolved;
        }

        public Kind getKind() {
            return kind;
        }

        public String getRawPath() {
            return rawPath;
        }

        /** The resolved target file, or {@code null} if it contains unresolved {@code ${...}} properties. */
        public File getResolved() {
            return resolved;
        }

        @Override
        public String toString() {
            return kind + " " + rawPath + (resolved == null ? " (unresolved)" : " -> " + resolved);
        }
    }

    private final File buildFile;
    private final String projectName;
    private final String defaultTarget;
    private final String declaredBasedir;
    private final List<Reference> references;

    private RawBuildFilePeek(
            File buildFile,
            String projectName,
            String defaultTarget,
            String declaredBasedir,
            List<Reference> references) {
        this.buildFile = buildFile;
        this.projectName = projectName;
        this.defaultTarget = defaultTarget;
        this.declaredBasedir = declaredBasedir;
        this.references = references;
    }

    public static RawBuildFilePeek peek(File buildFile) throws Exception {
        final File canonical = buildFile.getCanonicalFile();
        final File dir = canonical.getParentFile();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        // Discovery reads untrusted build files; disable external entity resolution.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(canonical);

        Element root = doc.getDocumentElement();
        String projectName = attrOrNull(root, "name");
        String defaultTarget = attrOrNull(root, "default");
        String declaredBasedir = attrOrNull(root, "basedir");

        // basedir defaults to the build file's own directory.
        File basedir = resolveBasedir(dir, declaredBasedir);

        List<Reference> refs = new ArrayList<>();
        collectReferences(root, dir, basedir, refs);

        return new RawBuildFilePeek(canonical, projectName, defaultTarget, declaredBasedir, refs);
    }

    private static void collectReferences(Node node, File importDir, File basedir, List<Reference> refs) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            switch (el.getTagName()) {
                case "import":
                    addRef(refs, Kind.IMPORT, attrOrNull(el, "file"), importDir);
                    break;
                case "include":
                    addRef(refs, Kind.INCLUDE, attrOrNull(el, "file"), importDir);
                    break;
                case "ant":
                    // <ant antfile=".." dir=".."> — antfile resolves relative to dir (default basedir).
                    addRef(refs, Kind.ANT, attrOrNull(el, "antfile"), antBaseDir(el, importDir, basedir));
                    break;
                case "subant":
                    // <subant antfile=".."> approximated relative to basedir; nested filesets not yet handled.
                    addRef(refs, Kind.SUBANT, attrOrNull(el, "antfile"), basedir);
                    break;
                default:
                    break;
            }
            collectReferences(el, importDir, basedir, refs);
        }
    }

    /** Resolve an {@code <ant>}'s base for antfile: its own {@code dir} attribute if present, else basedir. */
    private static File antBaseDir(Element antEl, File importDir, File basedir) {
        String dirAttr = attrOrNull(antEl, "dir");
        if (dirAttr == null || dirAttr.contains("${")) {
            return basedir;
        }
        File d = new File(dirAttr);
        return d.isAbsolute() ? d : new File(basedir, dirAttr);
    }

    private static void addRef(List<Reference> refs, Kind kind, String rawPath, File base) {
        if (rawPath == null || rawPath.isEmpty()) {
            return;
        }
        File resolved = resolve(rawPath, base);
        refs.add(new Reference(kind, rawPath, resolved));
    }

    /**
     * Resolve a reference path the way Ant does: absolute paths as-is, otherwise relative to
     * {@code base}. Paths containing unresolved {@code ${...}} properties cannot be resolved
     * statically and yield {@code null}.
     */
    private static File resolve(String rawPath, File base) {
        if (rawPath.contains("${")) {
            return null;
        }
        File f = new File(rawPath);
        if (!f.isAbsolute()) {
            f = new File(base, rawPath);
        }
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }

    private static File resolveBasedir(File buildFileDir, String declaredBasedir) {
        if (declaredBasedir == null || declaredBasedir.contains("${")) {
            return buildFileDir;
        }
        File b = new File(declaredBasedir);
        return b.isAbsolute() ? b : new File(buildFileDir, declaredBasedir);
    }

    private static String attrOrNull(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    public File getBuildFile() {
        return buildFile;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getDefaultTarget() {
        return defaultTarget;
    }

    public String getDeclaredBasedir() {
        return declaredBasedir;
    }

    public List<Reference> getReferences() {
        return references;
    }
}
