package org.jboss.pnc.antmanipulator.report;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.commonjava.atlas.maven.ident.ref.SimpleProjectVersionRef;
import org.jboss.pnc.antmanipulator.gav.CorrelatedGav;
import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.jboss.pnc.mavenmanipulator.common.json.ModulesItem;
import org.jboss.pnc.mavenmanipulator.common.json.PME;
import org.jboss.pnc.mavenmanipulator.common.util.JSONUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@code alignmentReport.json} in PME's own JSON shape — the {@code org.jboss.pnc.mavenmanipulator.common.json}
 * POJOs, reused verbatim from {@code pom-manipulation-common-lite} — so PNC/Repour can consume Ant Manipulator's
 * output exactly like PME's and GME's: a diff of the groupId:artifactId:version changes actually computed, not
 * our own richer pre-alignment discovery report (see {@link AlignmentReport} for that).
 *
 * <p>
 * Unlike a Maven reactor, an Ant build has no single canonical root GAV. {@code executionRoot} — which PME/Repour
 * use to compute "the project's" GAV change — is taken from whichever changed artifact has a finding sourced
 * directly from the root build file, falling back to the first changed artifact (by groupId:artifactId order) if
 * none is declared there.
 */
public final class PmeAlignmentReport {

    private enum DeclarationScope {
        ROOT_FILE,
        ROOT_DIR,
        OTHER
    }

    private static final Logger logger = LoggerFactory.getLogger(PmeAlignmentReport.class);

    private PmeAlignmentReport() {
    }

    /**
     * @param rootBuildFile the build file passed to {@code -f}, used to pick the {@code executionRoot} artifact
     * @param correlated every artifact this run discovered, correlated by groupId:artifactId
     * @param reconciler resolves a tokenised version to its concrete value(s)
     * @param versionRewrites base version literal -&gt; computed publish version, as applied by the rewrite step
     * @return the populated report; {@code getModules()} is empty when nothing was realigned
     */
    public static PME build(
            File rootBuildFile,
            GavCorrelator.Result correlated,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites) {

        PME report = new PME();
        ModulesItem rootFileModule = null;
        ModulesItem rootDirModule = null;

        for (CorrelatedGav artifact : correlated.getArtifacts()) {
            Realignment realignment = realignmentOf(artifact, reconciler, versionRewrites);
            if (realignment == null) {
                continue;
            }
            String originalVersion = realignment.originalVersion;
            String newVersion = realignment.newVersion;

            ModulesItem module = new ModulesItem();
            module.getGav()
                    .setOriginalGAV(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + originalVersion);
            module.getGav()
                    .setPVR(new SimpleProjectVersionRef(artifact.getGroupId(), artifact.getArtifactId(), newVersion));
            report.getModules().add(module);

            DeclarationScope scope = declarationScope(artifact, rootBuildFile);
            if (scope == DeclarationScope.ROOT_FILE && rootFileModule == null) {
                rootFileModule = module;
            } else if (scope == DeclarationScope.ROOT_DIR && rootDirModule == null) {
                rootDirModule = module;
            }
        }

        ModulesItem executionRootModule = rootFileModule != null
                ? rootFileModule
                : (rootDirModule != null
                        ? rootDirModule
                        : (report.getModules().isEmpty() ? null : report.getModules().get(0)));
        if (executionRootModule != null) {
            report.getGav().setOriginalGAV(executionRootModule.getGav().getOriginalGAV());
            report.getGav().setPVR(executionRootModule.getGav().getPVR());
        }

        return report;
    }

    /**
     * Builds a report for a run with version modification disabled ({@code -DversionModification=false}): nothing
     * was realigned, so there are no module entries, but PNC/Repour still needs the execution root's coordinate
     * to record and tag the build — under its <em>declared</em> (unchanged) version. The execution root is chosen
     * exactly as {@link #build} does (an artifact declared in the root build file or root directory, else the first
     * concrete one by groupId:artifactId order) and reported with its original version as both the before and after
     * value.
     *
     * @param rootBuildFile the build file passed to {@code -f}, used to pick the {@code executionRoot} artifact
     * @param correlated every artifact this run discovered, correlated by groupId:artifactId
     * @param reconciler resolves a tokenised version to its concrete value(s)
     * @return a report whose {@code executionRoot} is the unchanged project coordinate; empty if none is concrete
     */
    public static PME buildUnchanged(
            File rootBuildFile,
            GavCorrelator.Result correlated,
            VersionReconciler reconciler) {

        PME report = new PME();
        CorrelatedGav rootFileArtifact = null;
        String rootFileVersion = null;
        CorrelatedGav rootDirArtifact = null;
        String rootDirVersion = null;
        CorrelatedGav fallbackRoot = null;
        String fallbackRootVersion = null;

        for (CorrelatedGav artifact : correlated.getArtifacts()) {
            String version = concreteVersionOf(artifact, reconciler);
            if (version == null) {
                continue;
            }
            if (fallbackRoot == null) {
                fallbackRoot = artifact; // fallback: first concrete artifact, in groupId:artifactId order
                fallbackRootVersion = version;
            }
            DeclarationScope scope = declarationScope(artifact, rootBuildFile);
            if (scope == DeclarationScope.ROOT_FILE && rootFileArtifact == null) {
                rootFileArtifact = artifact;
                rootFileVersion = version;
            } else if (scope == DeclarationScope.ROOT_DIR && rootDirArtifact == null) {
                rootDirArtifact = artifact;
                rootDirVersion = version;
            }
        }

        CorrelatedGav executionRoot = rootFileArtifact != null
                ? rootFileArtifact
                : (rootDirArtifact != null ? rootDirArtifact : fallbackRoot);
        String executionRootVersion = rootFileArtifact != null
                ? rootFileVersion
                : (rootDirArtifact != null ? rootDirVersion : fallbackRootVersion);

        if (executionRoot != null) {
            report.getGav()
                    .setOriginalGAV(
                            executionRoot.getGroupId() + ":" + executionRoot.getArtifactId() + ":"
                                    + executionRootVersion);
            report.getGav()
                    .setPVR(
                            new SimpleProjectVersionRef(
                                    executionRoot.getGroupId(),
                                    executionRoot.getArtifactId(),
                                    executionRootVersion));
        }

        return report;
    }

    /**
     * The artifact's concrete version: its primary version if already concrete, else the (deterministically
     * chosen, release-preferring) first value a tokenised primary reconciles to, or {@code null} if it has no
     * usable groupId/artifactId or its version does not resolve.
     */
    private static String concreteVersionOf(CorrelatedGav artifact, VersionReconciler reconciler) {
        if (isBlank(artifact.getGroupId()) || isBlank(artifact.getArtifactId())) {
            return null;
        }
        return originalVersionOf(concreteCandidates(artifact, reconciler));
    }

    /**
     * The primary version's concrete candidates: every value a tokenised primary reconciles to, or the primary
     * itself (as a singleton) when it's already concrete. Empty when the artifact has no usable primary version,
     * or when a tokenised primary does not resolve. Shared by {@link #build} and {@link #buildUnchanged} so the
     * tokenised-candidate resolution lives in one place.
     */
    private static Set<String> concreteCandidates(CorrelatedGav artifact, VersionReconciler reconciler) {
        String primary = artifact.primaryVersion();
        if (primary == null) {
            return Collections.emptySet();
        }
        return artifact.tokenizedVersions().contains(primary)
                ? reconciler.reconcile(primary).getResolved()
                : Collections.singleton(primary);
    }

    /**
     * The deterministic, release-preferring "original version" for a set of concrete {@code candidates}: their
     * lexicographic minimum, or {@code null} when there are none. This prefers a release-looking candidate over
     * e.g. its {@code -SNAPSHOT} sibling, since a release version string is always a lexicographic prefix (and so
     * the minimum) of that sibling. Shared by {@link #build} and {@link #buildUnchanged} so the tie-break can't
     * silently diverge between the "changed" and "unchanged" report paths.
     */
    private static String originalVersionOf(Set<String> candidates) {
        return candidates.isEmpty() ? null : new TreeSet<>(candidates).first();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Write {@code report} to {@code file} using PME's own serialisation (pretty-printed, nulls/empties omitted). */
    public static File writeTo(PME report, File file) throws IOException {
        // UTF-8 explicitly (not JVM default charset) so non-ASCII coordinates serialise to the same bytes on
        // every platform, matching the discovery report and keeping the output parseable by PNC/Repour.
        Files.write(file.toPath(), JSONUtils.jsonToString(report).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final class Realignment {
        private final String originalVersion;
        private final String newVersion;

        private Realignment(String originalVersion, String newVersion) {
            this.originalVersion = originalVersion;
            this.newVersion = newVersion;
        }
    }

    /**
     * Whether, and how, this artifact was realigned. Its primary version's concrete candidates are either
     * itself (already concrete) or every value a tokenised version reconciles to — a tokenised version can
     * reconcile <em>ambiguously</em> (e.g. a release vs. its {@code -SNAPSHOT} variant both matching the same
     * filter token), which is fine as long as every candidate was realigned to the <em>same</em> new version;
     * if they diverge we can't confidently report a single before-value, so the artifact is skipped, mirroring
     * PME's refusal to guess.
     */
    private static Realignment realignmentOf(
            CorrelatedGav artifact,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites) {
        Set<String> candidates = concreteCandidates(artifact, reconciler);
        if (candidates.isEmpty()) {
            return null; // no usable primary version, or unresolved
        }

        Set<String> newVersions = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String newVersion = versionRewrites.get(candidate);
            if (newVersion != null) {
                newVersions.add(newVersion);
            }
        }
        if (newVersions.size() != 1) {
            return null; // not realigned, or candidates diverge on the realigned value
        }

        String newVersion = newVersions.iterator().next();
        String originalVersion = originalVersionOf(candidates);
        return newVersion.equals(originalVersion) ? null : new Realignment(originalVersion, newVersion);
    }

    private static DeclarationScope declarationScope(CorrelatedGav artifact, File rootBuildFile) {
        File canonicalRoot = canonical(rootBuildFile);
        File canonicalRootDir = canonicalRoot.getParentFile();
        boolean inRootDir = false;
        for (ResolvedGav finding : artifact.getFindings()) {
            File sourceFile = finding.getSourceFile();
            if (sourceFile != null) {
                File canonicalSource = canonical(sourceFile);
                if (canonicalSource.equals(canonicalRoot)) {
                    return DeclarationScope.ROOT_FILE;
                }
                if (canonicalRootDir != null && Objects.equals(canonicalSource.getParentFile(), canonicalRootDir)) {
                    inRootDir = true;
                }
            }
        }
        return inRootDir ? DeclarationScope.ROOT_DIR : DeclarationScope.OTHER;
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            // Falling back to the absolute (non-canonicalised) path may make isDeclaredIn() miss the root
            // build file and so pick a different executionRoot, so leave a trace of why.
            logger.debug("Could not canonicalise {}; falling back to absolute path: {}", f, e.getMessage());
            return f.getAbsoluteFile();
        }
    }
}
