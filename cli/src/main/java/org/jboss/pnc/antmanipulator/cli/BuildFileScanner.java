package org.jboss.pnc.antmanipulator.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the set of Ant build files reachable from a root build file, by following
 * {@code <import>}/{@code <include>}/{@code <ant>}/{@code <subant>} references transitively.
 *
 * <p>
 * This is the Ant analogue of pom-manipulation-ext's {@code PomIO.peekAtPomHierarchy}. PME walks
 * a POM's {@code <parent>}/{@code <modules>} edges breadth-first, deduplicating and skipping
 * non-existent references; this does the same over Ant's inter-build-file links.
 *
 * <p>
 * Key differences from the Maven case:
 * <ul>
 * <li>Ant has no single well-defined "project graph"; links come from tasks, and paths may contain
 * {@code ${...}} properties that cannot be resolved statically. Such links are reported as
 * {@link ScanResult#getUnresolved() unresolved} rather than followed.</li>
 * <li>Ant imports frequently cross directory boundaries, so — unlike PME, which confines the walk
 * to the root's directory subtree — references outside the root tree are still followed but
 * flagged via {@link ScanResult#getOutsideRootTree()} so a caller can decide whether to touch
 * them.</li>
 * </ul>
 */
public class BuildFileScanner {

    private static final Logger logger = LoggerFactory.getLogger(BuildFileScanner.class);

    /** Outcome of a hierarchy crawl. */
    public static final class ScanResult {
        private final List<RawBuildFilePeek> peeks;
        private final List<RawBuildFilePeek.Reference> missing;
        private final List<RawBuildFilePeek.Reference> unresolved;
        private final Set<File> outsideRootTree;

        ScanResult(
                List<RawBuildFilePeek> peeks,
                List<RawBuildFilePeek.Reference> missing,
                List<RawBuildFilePeek.Reference> unresolved,
                Set<File> outsideRootTree) {
            this.peeks = peeks;
            this.missing = missing;
            this.unresolved = unresolved;
            this.outsideRootTree = outsideRootTree;
        }

        /** All discovered build files, root first, in breadth-first discovery order. */
        public List<RawBuildFilePeek> getPeeks() {
            return peeks;
        }

        /** References that resolved to a concrete path which does not exist on disk. */
        public List<RawBuildFilePeek.Reference> getMissing() {
            return missing;
        }

        /** References that could not be resolved statically (e.g. contained {@code ${...}}). */
        public List<RawBuildFilePeek.Reference> getUnresolved() {
            return unresolved;
        }

        /** Discovered files that live outside the root build file's directory subtree. */
        public Set<File> getOutsideRootTree() {
            return outsideRootTree;
        }
    }

    /**
     * Crawl the build-file hierarchy starting at {@code root}.
     *
     * @param root the execution-root build file (the analogue of PME's execution-root POM)
     * @return the discovered hierarchy and any references that could not be followed
     * @throws IOException if the root file cannot be canonicalised
     */
    public ScanResult crawl(final File root) throws IOException {
        final File canonicalRoot = root.getCanonicalFile();
        final String topDir = canonicalRoot.getParentFile().getCanonicalPath();

        final List<RawBuildFilePeek> peeked = new ArrayList<>();
        final List<RawBuildFilePeek.Reference> missing = new ArrayList<>();
        final List<RawBuildFilePeek.Reference> unresolved = new ArrayList<>();
        final Set<File> outsideRootTree = new LinkedHashSet<>();

        final LinkedList<File> pending = new LinkedList<>();
        final Set<File> seen = new LinkedHashSet<>();
        pending.add(canonicalRoot);

        while (!pending.isEmpty()) {
            final File current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }

            logger.debug("PEEK: {}", current);

            final RawBuildFilePeek peek;
            try {
                peek = RawBuildFilePeek.peek(current);
            } catch (Exception e) {
                logger.warn("Failed to peek at build file {}: {}", current, e.getMessage());
                continue;
            }
            peeked.add(peek);

            for (RawBuildFilePeek.Reference ref : peek.getReferences()) {
                final File resolved = ref.getResolved();
                if (resolved == null) {
                    logger.debug("Cannot statically resolve {} in {}", ref.getRawPath(), current);
                    unresolved.add(ref);
                    continue;
                }
                if (!resolved.exists()) {
                    logger.debug(
                            "Skipping non-existent reference {} ({}) in {}",
                            ref.getRawPath(),
                            resolved,
                            current);
                    missing.add(ref);
                    continue;
                }

                final String resolvedParent = resolved.getParentFile().getCanonicalPath();
                if (!resolvedParent.startsWith(topDir)) {
                    outsideRootTree.add(resolved);
                }

                if (!seen.contains(resolved) && !pending.contains(resolved)) {
                    logger.debug("Found {} reference: {} in {}", ref.getKind(), resolved, current);
                    pending.addLast(resolved);
                }
            }
        }

        return new ScanResult(peeked, missing, unresolved, outsideRootTree);
    }
}
