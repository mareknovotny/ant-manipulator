package org.jboss.pnc.antmanipulator.gav;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Directories under a project root that have their own {@code build.xml} which Ant never actually
 * reaches from the execution-root build file (GitHub issue #44) — a self-contained Ant project bundled
 * alongside the real sources (e.g. an abandoned sandbox proposal), rather than a real part of this
 * build. {@link GavResolver}, {@link VersionReconciler}, and {@link VersionRewriter} each walk the
 * project tree independently and must agree on this: a directory excluded from coordinate discovery
 * must also never be rewritten, or have its {@code <filter>}/{@code <property>} definitions folded into
 * reconciliation.
 */
final class UnreferencedBuildDirs {

    private static final Logger logger = LoggerFactory.getLogger(UnreferencedBuildDirs.class);

    private UnreferencedBuildDirs() {
    }

    /**
     * Directories (other than {@code rootDir} itself) that have their own {@code build.xml} which is not
     * in {@code reachableBuildFiles}. Returns an empty set (nothing excluded) when
     * {@code reachableBuildFiles} is {@code null}, e.g. because the build-file hierarchy wasn't crawled.
     *
     * @param reachableBuildFiles canonical build files Ant actually reaches from the root (e.g. from
     *        {@code BuildFileScanner.ScanResult.getPeeks()}), or {@code null}
     */
    static Set<Path> find(Path rootDir, Set<File> reachableBuildFiles) throws IOException {
        if (reachableBuildFiles == null) {
            return Collections.emptySet();
        }
        final Set<Path> reachable = new LinkedHashSet<>();
        for (File f : reachableBuildFiles) {
            reachable.add(f.getCanonicalFile().toPath());
        }

        final Set<Path> excluded = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(rootDir)) {
            walk.filter(Files::isDirectory)
                    .filter(d -> !d.equals(rootDir))
                    .forEach(d -> {
                        Path buildXml = d.resolve("build.xml");
                        if (Files.isRegularFile(buildXml)) {
                            try {
                                if (!reachable.contains(buildXml.toRealPath())) {
                                    excluded.add(d);
                                }
                            } catch (IOException e) {
                                logger.warn("Could not canonicalise {}: {}", buildXml, e.getMessage());
                            }
                        }
                    });
        }
        return excluded;
    }

    /** True when {@code file} sits anywhere under one of {@code excludedDirs}. */
    static boolean isUnder(Set<Path> excludedDirs, Path file) {
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (excludedDirs.contains(dir)) {
                return true;
            }
        }
        return false;
    }
}
