package org.jboss.pnc.antmanipulator.align;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.pnc.mavenmanipulator.common.Version;
import org.jboss.pnc.mavenmanipulator.common.exception.ManipulationException;

/**
 * Computes the version a project should be <em>published</em> under — the analogue of PME's
 * {@code VersionCalculator}. Alignment of a project's own coordinate is not "swap in an existing
 * rebuild" (that is dependency alignment, DA's {@code bestMatchVersion}); it is "assign the next free
 * build number", because you are about to build and publish it.
 *
 * <p>
 * The input is the project's declared base version plus DA's {@code latestVersion} — the highest
 * <em>existing</em> build of that coordinate (from {@code lookup/maven/latest}), or {@code null} if it
 * has never been built. From those:
 *
 * <ul>
 * <li><b>An existing build exists</b> (e.g. {@code 3.8-beta1.redhat-00002}): increment its serial,
 * preserving DA's already-normalised stem, separator and zero-padding →
 * {@code 3.8-beta1.redhat-00003}. This is exact — we never re-derive what DA already computed, unlike
 * PME's own {@code VersionCalculator.calculate()}, which re-derives a match pattern from the
 * <em>declared</em> version and scans a candidate set; that's the wrong model for us, since DA already
 * tells us the one authoritative prior build directly, and re-deriving risks silently resetting the
 * serial to 1 when the declared version has drifted from DA's last-known build.</li>
 * <li><b>First build</b> ({@code latestVersion} null): synthesise the suffixed version via PME's own
 * {@code Version.appendQualifierSuffix()} + {@code Version.getOsgiVersion()} — the same two calls
 * {@code VersionCalculator.calculate()} makes for a first build. This zero-fills a short
 * major[.minor[.micro]] stem to 3 components (→ {@code 4.9} becomes {@code 4.9.0.redhat-00001}) and
 * always normalises the qualifier delimiter to {@code .} (→ {@code 1.0.0-M1} becomes
 * {@code 1.0.0.M1-redhat-00001}). This is still best-effort: PNC assigns the authoritative first-build
 * version.</li>
 * </ul>
 */
public final class VersionIncrementer {

    /**
     * Default zero-padding width of the rebuild serial (e.g. {@code 00001}), matching PME. There is deliberately
     * no default suffix: as in PME, the suffix is opt-in and must always be supplied by the caller.
     */
    public static final int DEFAULT_PADDING = 5;

    /**
     * Development-build markers stripped like {@code -SNAPSHOT} — dropped entirely, never carried into (or
     * re-appended after) the rebuild version. Comma-separated. Defaults to <em>empty</em> (no stripping):
     * a {@code -dev} tail is kept by default (→ {@code 10.1.0-dev-redhat-00001}). Opt in by configuring
     * markers, e.g. {@code dev} — the tail Tomcat appends to its in-development install version
     * ({@code 10.1.0-dev}) — to make it publish under the same version as the release path
     * ({@code 10.1.0.redhat-00001}). PME has no equivalent concept, so this stripping always happens
     * ourselves, before any PME {@code Version} call.
     */
    public static final String DEFAULT_DEV_MARKERS = "";

    private final String suffixBase;
    private final int padding;
    private final boolean preserveSnapshot;
    private final Pattern suffixTail;
    /** Trailing {@code <sep><marker>} for a configured dev marker (case-insensitive), or null when none. */
    private final Pattern devTail;

    public VersionIncrementer(String suffixBase, int padding) {
        this(suffixBase, padding, false);
    }

    public VersionIncrementer(String suffixBase, int padding, boolean preserveSnapshot) {
        this(suffixBase, padding, preserveSnapshot, DEFAULT_DEV_MARKERS);
    }

    /**
     * @param suffixBase the rebuild suffix (e.g. {@code redhat}); must be non-blank — there is no default, and an
     *        empty suffix (PME's bare-counter form) is not supported
     * @param padding zero-padding width of the rebuild serial (e.g. {@code 5} gives {@code 00001}); values
     *        {@code <= 0} fall back to {@link #DEFAULT_PADDING}
     * @param preserveSnapshot PME's {@code versionSuffixSnapshot}: when {@code true}, a stripped
     *        {@code -SNAPSHOT} is re-appended at the very end (after the rebuild
     *        number), e.g. {@code 12.0.0-M1-redhat-00001-SNAPSHOT}; when {@code false}
     *        (PME default) SNAPSHOT is dropped entirely.
     * @param devMarkers comma-separated development markers to strip like SNAPSHOT (dropped, never
     *        re-appended); e.g. {@code dev} turns {@code 10.1.0-dev} into base {@code 10.1.0}. {@code null}
     *        or blank disables dev stripping.
     * @throws IllegalArgumentException if {@code suffixBase} is null or blank
     */
    public VersionIncrementer(String suffixBase, int padding, boolean preserveSnapshot, String devMarkers) {
        if (suffixBase == null || suffixBase.trim().isEmpty()) {
            throw new IllegalArgumentException("the version suffix must not be null or empty.");
        }
        this.suffixBase = suffixBase;
        this.padding = padding > 0 ? padding : DEFAULT_PADDING;
        this.preserveSnapshot = preserveSnapshot;
        // Matches a trailing "<sep>redhat-<serial>", e.g. ".redhat-00002" or "-redhat-3". Deliberately
        // our own (not PME's suffix-agnostic Version.getBuildNumber()): this must only recognise *our
        // configured* suffixBase, not any trailing digit run, since a DA latestVersion with a different
        // or absent suffix is handled by the separate "no recognisable suffix" branch below.
        this.suffixTail = Pattern.compile("([.-])" + Pattern.quote(suffixBase) + "-(\\d+)$");
        this.devTail = compileDevTail(devMarkers);
    }

    /** Builds a case-insensitive "trailing {@code <sep>(m1|m2|...)}" pattern, or null when no markers. */
    private static Pattern compileDevTail(String devMarkers) {
        if (devMarkers == null) {
            return null;
        }
        List<String> quoted = new ArrayList<>();
        for (String marker : devMarkers.split(",")) {
            String m = marker.trim();
            if (!m.isEmpty()) {
                quoted.add(Pattern.quote(m));
            }
        }
        if (quoted.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?i)[.-](" + String.join("|", quoted) + ")$");
    }

    /** The outcome of computing a project's publish version. */
    public static final class Result {
        private final String newVersion;
        private final boolean firstBuild;
        private final String basedOn; // DA's latestVersion, or null for a first build

        Result(String newVersion, boolean firstBuild, String basedOn) {
            this.newVersion = newVersion;
            this.firstBuild = firstBuild;
            this.basedOn = basedOn;
        }

        /** The version to publish under. */
        public String getNewVersion() {
            return newVersion;
        }

        /** True when DA had no prior build and this is the synthesised first build. */
        public boolean isFirstBuild() {
            return firstBuild;
        }

        /** DA's {@code latestVersion} this was incremented from, or {@code null} for a first build. */
        public String getBasedOn() {
            return basedOn;
        }

        /** A one-line, human-readable outcome suitable for logging. */
        public String summarize() {
            return firstBuild
                    ? newVersion + "  (first build)"
                    : newVersion + "  (increment of " + basedOn + ")";
        }
    }

    /**
     * @param baseVersion the project's declared version (e.g. {@code 3.8-beta1}); used only for a first build
     * @param daLatest DA's {@code latestVersion} for this coordinate, or {@code null} if never built
     * @return the computed publish version
     * @throws ManipulationException if no usable base version remains after stripping SNAPSHOT/dev markers
     *         and/or the rebuild suffix (e.g. a declared version that is literally {@code SNAPSHOT}, or a
     *         blank/missing one), so no publish version can be synthesised. This is a hard failure rather
     *         than a logged warning: silently leaving the coordinate's old version in place would risk
     *         publishing it unchanged with nothing but a log line to notice by, so instead we force the
     *         user to fix the declared version (or DA's recorded build) before the run can proceed. (PME's
     *         own {@code VersionCalculator} has this same gap for a bare {@code SNAPSHOT} — confirmed
     *         empirically it produces {@code .redhat-00001} — so adopting its code wouldn't have solved
     *         this for us either; it still needs to be caught explicitly.)
     */
    public Result nextVersion(String baseVersion, String daLatest) throws ManipulationException {
        if (daLatest != null && !daLatest.trim().isEmpty()) {
            String latest = daLatest.trim();
            // Follow PME's removeSnapshot: never carry -SNAPSHOT through into the rebuild version.
            // Dev markers (e.g. -dev) are stripped the same way, but always dropped (never re-appended).
            boolean hadSnapshot = Version.isSnapshot(latest);
            String latestStem = stripDevTails(Version.removeSnapshot(latest));
            Matcher m = suffixTail.matcher(latestStem);
            if (m.find()) {
                String sep = m.group(1);
                String serialText = m.group(2);
                int next = Integer.parseInt(serialText) + 1;
                int width = Math.max(serialText.length(), padding);
                String stem = latestStem.substring(0, m.start());
                // DA's latestVersion is just a bare "<suffix>-<serial>" with no stem of its own
                // (malformed/unexpected DA data): don't synthesise "-redhat-<serial>".
                requireUsableStem(stem, latest);
                return new Result(
                        withSnapshot(stem + sep + suffixBase + "-" + pad(next, width), hadSnapshot),
                        false,
                        latest);
            }
            // DA's latestVersion may reduce to nothing once SNAPSHOT/dev markers are stripped (e.g. a
            // bare "SNAPSHOT"): there's no real base to build the first serial from.
            requireUsableStem(latestStem, latest);
            // DA returned a version without a recognisable redhat suffix: treat it as the stem for
            // the first serial rather than the declared base (DA's form is the authoritative one).
            return new Result(firstBuildOf(latestStem, hadSnapshot), true, latest);
        }
        String base = baseVersion == null ? "" : baseVersion.trim();
        boolean hadSnapshot = Version.isSnapshot(base);
        String baseStem = stripDevTails(Version.removeSnapshot(base));
        // The declared version may be blank, or literally (or reduce to) "SNAPSHOT": there's no real
        // base to build the first serial from.
        requireUsableStem(baseStem, baseVersion);
        return new Result(firstBuildOf(baseStem, hadSnapshot), true, null);
    }

    /** @throws ManipulationException if {@code stem} has no usable content once trimmed */
    private static void requireUsableStem(String stem, String original) throws ManipulationException {
        if (stem == null || stem.trim().isEmpty()) {
            throw new ManipulationException(
                    "Cannot compute a publish version from '" + original + "': no usable base version "
                            + "remains after stripping SNAPSHOT/dev markers and/or the rebuild suffix. "
                            + "Fix the declared version.");
        }
    }

    /**
     * Strips any trailing configured dev marker(s), e.g. {@code 10.1.0-dev} → {@code 10.1.0}. Applied
     * repeatedly so stacked markers ({@code 1.0-dev-dev}) collapse fully. No-op when none are configured.
     */
    private String stripDevTails(String version) {
        if (devTail == null) {
            return version;
        }
        String current = version;
        Matcher m = devTail.matcher(current);
        while (m.find()) {
            current = current.substring(0, m.start());
            m = devTail.matcher(current);
        }
        return current;
    }

    /**
     * Synthesises {@code <stem><sep>redhat-00001} exactly as PME's {@code VersionCalculator.calculate()}
     * does for a first build: append the rebuild number as a qualifier suffix, then OSGi-normalise the
     * result. Safe to delegate to PME here (unlike the increment-existing-build path above) because
     * there's no candidate set to mismatch against — {@code stem} is the only input.
     */
    private String firstBuildOf(String stem, boolean hadSnapshot) {
        String suffixed = Version.appendQualifierSuffix(stem, suffixBase + "-" + pad(1, padding));
        return withSnapshot(Version.getOsgiVersion(suffixed), hadSnapshot);
    }

    /**
     * Re-applies a stripped {@code -SNAPSHOT} at the very end, PME-style, but only when
     * {@code preserveSnapshot} is set; otherwise SNAPSHOT stays dropped (PME's default).
     */
    private String withSnapshot(String version, boolean hadSnapshot) {
        return (preserveSnapshot && hadSnapshot) ? Version.setSnapshot(version, true) : version;
    }

    private static String pad(int serial, int width) {
        return String.format("%0" + width + "d", serial);
    }
}
