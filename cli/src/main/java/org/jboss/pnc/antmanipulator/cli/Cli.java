package org.jboss.pnc.antmanipulator.cli;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

import org.jboss.pnc.antmanipulator.align.DefaultTranslator;
import org.jboss.pnc.antmanipulator.align.Gav;
import org.jboss.pnc.antmanipulator.align.Translator;
import org.jboss.pnc.antmanipulator.align.VersionIncrementer;
import org.jboss.pnc.antmanipulator.gav.CorrelatedGav;
import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.GavResolver;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.jboss.pnc.antmanipulator.gav.VersionRewriter;
import org.jboss.pnc.antmanipulator.report.AlignmentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Command-line entry point for the Ant manipulator.
 *
 * <p>
 * Modelled on {@code org.jboss.pnc.mavenmanipulator.cli.Cli} from pom-manipulation-ext: a picocli
 * command whose {@link #call()} is the real body, invoked from {@link #main(String[])}. The key option is
 * {@code -f/--file}, which points at the Ant build file to operate against (the equivalent of PME's
 * {@code pom.xml} execution root).
 */
@Command(
        name = "AME",
        description = "CLI to run the Ant Manipulation Extension",
        mixinStandardHelpOptions = true, // adds --help and --version
        exitCodeOnInvalidInput = 10)
public class Cli implements Callable<Integer> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // -D property keys, matching pom-manipulation-ext (RESTState/VersioningState) and gradle-manipulator
    // verbatim so the same argument string is portable across the three tools.
    static final String PROP_REST_URL = "restURL";
    static final String PROP_REST_MODE = "restMode";
    static final String PROP_REST_BREW_PULL = "restBrewPullActive";
    static final String PROP_REST_SOCKET_TIMEOUT = "restSocketTimeout";
    static final String PROP_REST_HEADERS = "restHeaders";
    static final String PROP_VERSION_INCREMENTAL_SUFFIX = "versionIncrementalSuffix";
    static final String PROP_VERSION_INCREMENTAL_SUFFIX_PADDING = "versionIncrementalSuffixPadding";
    static final String PROP_REPORT_JSON = "reportJSONOutputFile";
    /** antalignment-specific (no PME equivalent): dev markers stripped like {@code -SNAPSHOT}. */
    static final String PROP_VERSION_SUFFIX_STRIP = "versionSuffixStrip";

    /**
     * The build file to operate against. Defaults to {@code build.xml} in the current directory,
     * mirroring PME's default of {@code pom.xml}.
     */
    @SuppressWarnings("FieldMayBeFinal")
    @Option(names = { "-f", "--file" }, description = "Ant build file (default: ./build.xml)")
    private File target = new File(System.getProperty("user.dir"), "build.xml");

    @Option(names = { "-d", "--debug" }, description = "Enable debug logging")
    private boolean debug;

    /**
     * All configuration, PME/GME-style ({@code -Dkey=value}). This single bag carries both:
     * <ul>
     * <li>the alignment injection keys ({@code -Dalignment.groupId}, {@code -Dalignment.artifactId},
     * {@code -Dalignment.version}) that let you add coordinates a project never declares (e.g. Xalan);</li>
     * <li>the REST/DA and version-calculation keys defined by pom-manipulation-ext and gradle-manipulator
     * ({@code restURL}, {@code restMode}, {@code restHeaders}, {@code versionIncrementalSuffix}, …).</li>
     * </ul>
     * Using the exact PME/GME key names means the same argument string works across all three tools; the
     * recognised keys are the {@code PROP_*} constants below.
     */
    @Option(
            names = "-D",
            mapFallbackValue = "true",
            description = "Config properties, e.g. -DrestURL=... -DrestMode=PERSISTENT -DversionIncrementalSuffix=redhat")
    private final Properties properties = new Properties();

    /**
     * Whether to only <em>preview</em> the rewrite rather than apply it. Off by default: like PME/GME, the
     * computed publish versions are written back into the build files in place (PME's
     * {@code PomIO.rewritePOMs} analogue; no backups — this assumes the build files are under version
     * control). With {@code --preview} the rewrite step just logs the edits it would make and touches
     * nothing. PME/GME have no such flag (they always write), so this stays a flag rather than a
     * {@code -D} property.
     */
    @Option(
            names = { "--preview" },
            description = "Only show the edits that would be made; do not modify any files")
    private boolean preview;

    public static void main(String[] args) {
        System.exit(new Cli().run(args));
    }

    /**
     * Public so external tools can invoke the CLI programmatically.
     *
     * @param args command-line arguments
     * @return the process exit code
     */
    public int run(String[] args) {
        CommandLine cl = new CommandLine(this);
        cl.setUsageHelpAutoWidth(true);
        return cl.execute(args);
    }

    @Override
    public Integer call() {
        if (debug) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        }

        final File buildFile = target.getAbsoluteFile();

        if (!buildFile.exists()) {
            logger.error("Manipulation engine disabled. Build file {} cannot be found.", buildFile);
            return 10;
        }
        if (buildFile.isDirectory()) {
            logger.error("Target {} is a directory, not an Ant build file.", buildFile);
            return 10;
        }

        logger.info("Operating against Ant build file: {}", buildFile);

        // Step 1 (the analogue of PME's PomIO.peekAtPomHierarchy): crawl the build-file hierarchy so
        // we know every file we may need to manipulate before touching anything.
        try {
            BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(buildFile);

            logger.info("Discovered {} build file(s) in the hierarchy:", scan.getPeeks().size());
            for (RawBuildFilePeek peek : scan.getPeeks()) {
                logger.info(
                        "    {} [{}]",
                        peek.getBuildFile(),
                        peek.getProjectName() == null ? "<unnamed>" : peek.getProjectName());
                for (RawBuildFilePeek.Reference ref : peek.getReferences()) {
                    logger.info("        {}", ref);
                }
            }
            if (!scan.getOutsideRootTree().isEmpty()) {
                logger.warn(
                        "{} build file(s) live outside the root directory tree:",
                        scan.getOutsideRootTree().size());
                scan.getOutsideRootTree().forEach(f -> logger.warn("    ! {}", f));
            }
            if (!scan.getMissing().isEmpty()) {
                logger.warn("{} reference(s) point to non-existent files.", scan.getMissing().size());
            }
            if (!scan.getUnresolved().isEmpty()) {
                logger.warn(
                        "{} reference(s) could not be resolved statically (contain ${{...}}).",
                        scan.getUnresolved().size());
            }
        } catch (Exception e) {
            logger.error("Failed to crawl build file hierarchy from {}: {}", buildFile, e.getMessage(), e);
            return 10;
        }

        // Step 2 (the analogue of reading GAV off the Maven model): Ant has no canonical place for
        // coordinates and one build may publish many, so we gather every candidate with provenance.
        try {
            List<ResolvedGav> gavs = new GavResolver().resolve(buildFile, properties);

            // Step 3: correlate findings by groupId:artifactId so per-artifact conflicts are explicit.
            GavCorrelator.Result correlated = new GavCorrelator().correlate(gavs);

            logger.info(
                    "Correlated {} coordinate candidate(s) into {} artifact(s):",
                    gavs.size(),
                    correlated.getArtifacts().size());
            for (CorrelatedGav artifact : correlated.getArtifacts()) {
                logger.info("    {}", artifact.summarize());
            }

            List<CorrelatedGav> conflicts = correlated.getConflicts();
            if (!conflicts.isEmpty()) {
                logger.warn("{} artifact(s) have conflicting concrete versions:", conflicts.size());
                for (CorrelatedGav c : conflicts) {
                    logger.warn("    {} -> {}", c.ga(), c.resolvedVersions());
                    for (ResolvedGav f : c.getFindings()) {
                        logger.warn("        {}", f);
                    }
                }
            }

            List<ResolvedGav> projectVersions = correlated.getProjectVersionCandidates();
            if (!projectVersions.isEmpty()) {
                logger.info("Project-level version candidate(s) (no groupId:artifactId):");
                for (ResolvedGav pv : projectVersions) {
                    logger.info("    {}", pv);
                }
            }

            // Step 4: reconcile tokenised versions to concrete values by following
            // @TOKEN@ -> <filter> -> ${property} chains (the state Maven's model gives PME for free).
            VersionReconciler reconciler = VersionReconciler
                    .forTree(buildFile.getParentFile(), buildFile, properties);

            Set<String> tokenised = new LinkedHashSet<>();
            for (ResolvedGav g : gavs) {
                if (g.hasUnresolvedVersion()) {
                    tokenised.add(g.getVersion());
                }
            }
            if (!tokenised.isEmpty()) {
                logger.info("Reconciling {} tokenised version(s):", tokenised.size());
                for (String token : tokenised) {
                    logger.info("    {}", reconciler.reconcile(token).summarize());
                }
            }

            // Step 5 (PME's alignmentReport.json analogue): write a machine-readable report of every
            // coordinate, its provenance, conflicts, and reconciliation.
            String reportPath = properties.getProperty(PROP_REPORT_JSON);
            File out = reportPath != null && !reportPath.trim().isEmpty()
                    ? new File(reportPath.trim())
                    : new File(buildFile.getParentFile(), "alignment-report.json");
            AlignmentReport.build(buildFile, gavs, correlated, reconciler).writeTo(out);
            logger.info("Wrote alignment report to {}", out.getAbsolutePath());

            // Step 6 (PME's Translator/DA round-trip + VersionCalculator): the discovered coordinates are
            // the project's OWN publishing coordinates, so aligning them means computing the version to
            // publish under — not swapping in an existing rebuild (that is dependency alignment). We ask DA's
            // lookup/maven/latest endpoint for the highest existing build, then increment its serial (or
            // synthesise redhat-00001 for a first build). The external source of truth decides the base,
            // exactly as PME delegates to the Dependency Analyzer.
            String restUrl = properties.getProperty(PROP_REST_URL);
            if (restUrl != null && !restUrl.trim().isEmpty()) {
                List<Gav> coordinates = concreteCoordinates(gavs, reconciler);
                if (coordinates.isEmpty()) {
                    logger.warn("No concrete coordinates to align; skipping lookup.");
                } else {
                    logger.info("Aligning {} project coordinate(s) against {}", coordinates.size(), restUrl);
                    Translator translator = new DefaultTranslator(
                            restUrl,
                            parseRestHeaders(properties.getProperty(PROP_REST_HEADERS)),
                            properties.getProperty(PROP_REST_MODE),
                            boolProp(PROP_REST_BREW_PULL),
                            128,
                            intProp(PROP_REST_SOCKET_TIMEOUT));
                    Map<Gav, String> latest = translator.lookupProjectVersions(coordinates);
                    VersionIncrementer incrementer = new VersionIncrementer(
                            properties.getProperty(PROP_VERSION_INCREMENTAL_SUFFIX, VersionIncrementer.DEFAULT_SUFFIX),
                            intProp(PROP_VERSION_INCREMENTAL_SUFFIX_PADDING, VersionIncrementer.DEFAULT_PADDING),
                            false,
                            properties.getProperty(PROP_VERSION_SUFFIX_STRIP, VersionIncrementer.DEFAULT_DEV_MARKERS));

                    // Collect base version literal -> computed publish version, for the rewrite step.
                    Map<String, String> versionRewrites = new LinkedHashMap<>();
                    logger.info("Computed publish version(s) for {} coordinate(s):", coordinates.size());
                    for (Gav g : coordinates) {
                        String daLatest = latest.get(g); // null when DA has no prior build
                        VersionIncrementer.Result r = incrementer.nextVersion(g.getVersion(), daLatest);
                        logger.info("    {} -> {}", g, r.summarize());

                        String base = g.getVersion();
                        String prior = versionRewrites.putIfAbsent(base, r.getNewVersion());
                        if (prior != null && !prior.equals(r.getNewVersion())) {
                            // Same literal defines two coordinates that align differently; we can't rewrite
                            // one literal to two values, so keep the first and flag it rather than guess.
                            logger.warn(
                                    "Version literal {} maps to both {} and {}; rewriting to {} only.",
                                    base,
                                    prior,
                                    r.getNewVersion(),
                                    prior);
                        }
                    }

                    // Step 7 (PME's PomIO.rewritePOMs analogue): apply the computed publish versions back to
                    // the build files at their definition sites. Applies in place by default; --preview skips.
                    rewriteVersions(buildFile.getParentFile(), versionRewrites, reconciler);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to resolve GAV coordinates from {}: {}", buildFile, e.getMessage(), e);
            return 10;
        }

        return 0;
    }

    /**
     * Build the list of concrete coordinates worth sending to the lookup service: every finding that has
     * a groupId and artifactId and either a concrete version or a tokenised one that reconciles to
     * concrete value(s). When a version is target-dependent (reconciles ambiguously, e.g. a release vs a
     * {@code -SNAPSHOT} variant) we send <em>every</em> resolved variant — the lookup is read-only, so
     * asking about each and letting the service report which it knows is strictly more informative than
     * dropping the coordinate. (Picking a single winner is the rewrite step's job, not the lookup's.)
     * Unresolved versions are skipped. Deduplicated, order preserved.
     */
    private static List<Gav> concreteCoordinates(List<ResolvedGav> gavs, VersionReconciler reconciler) {
        Set<Gav> out = new LinkedHashSet<>();
        for (ResolvedGav g : gavs) {
            String groupId = g.getGroupId();
            String artifactId = g.getArtifactId();
            if (isBlank(groupId) || isBlank(artifactId)) {
                continue; // project-version candidates and partial findings can't be aligned
            }

            String version = g.getVersion();
            if (version != null && !g.hasUnresolvedVersion()) {
                out.add(new Gav(groupId, artifactId, version));
                continue;
            }

            if (version != null) {
                VersionReconciler.Resolution r = reconciler.reconcile(version);
                for (String resolved : r.getResolved()) {
                    out.add(new Gav(groupId, artifactId, resolved));
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * Locate the definition sites of each base version literal and apply the computed publish version there
     * (or, with {@code --preview}, just report them). Reports every planned edit so the user can verify that only
     * version-defining positions — not prose or unrelated properties — are being touched.
     */
    private void rewriteVersions(File rootDir, Map<String, String> versionRewrites, VersionReconciler reconciler)
            throws java.io.IOException {
        if (versionRewrites.isEmpty()) {
            return;
        }
        List<VersionRewriter.Edit> edits = VersionRewriter.plan(rootDir, versionRewrites, reconciler);
        if (edits.isEmpty()) {
            logger.info(
                    "No rewritable version definition site found for the computed version(s); "
                            + "the project version may be composed at build time and cannot be rewritten statically.");
            return;
        }

        logger.info(
                "{} the following {} version definition site(s):",
                preview ? "Would rewrite (preview; omit --preview to apply)" : "Rewriting",
                edits.size());
        for (VersionRewriter.Edit e : edits) {
            logger.info("    {}", e.summarize());
        }

        if (!preview) {
            List<File> changed = VersionRewriter.apply(edits);
            logger.info("Wrote {} file(s):", changed.size());
            for (File f : changed) {
                logger.info("    {}", f);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Parses PME/GME's {@code restHeaders} value: comma-separated {@code name:value} pairs, each split on
     * its first {@code :} (so header values may themselves contain colons, e.g. {@code Bearer eyJ...}).
     * Returns an insertion-ordered map; blank/null yields an empty map.
     */
    static Map<String, String> parseRestHeaders(String value) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (value == null || value.trim().isEmpty()) {
            return headers;
        }
        for (String pair : value.split(",")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon < 0) {
                headers.put(entry, "");
            } else {
                headers.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** {@code true} only when the property is present and parses truthy; unset stays {@code null}. */
    private Boolean boolProp(String key) {
        String v = properties.getProperty(key);
        return v == null ? null : Boolean.valueOf(v.trim());
    }

    /** The property parsed as an int, or {@code null} when unset. */
    private Integer intProp(String key) {
        String v = properties.getProperty(key);
        return v == null || v.trim().isEmpty() ? null : Integer.valueOf(v.trim());
    }

    /** The property parsed as an int, or {@code fallback} when unset/blank. */
    private int intProp(String key, int fallback) {
        Integer v = intProp(key);
        return v == null ? fallback : v;
    }
}
