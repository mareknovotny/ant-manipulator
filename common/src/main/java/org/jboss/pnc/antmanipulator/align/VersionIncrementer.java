package org.jboss.pnc.antmanipulator.align;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * {@code 3.8-beta1.redhat-00003}. This is exact — we never re-derive what DA already computed.</li>
 * <li><b>First build</b> ({@code latestVersion} null): synthesise {@code <base><sep>redhat-00001}. The
 * separator mirrors PME/OSGi: {@code .} when the version tail is numeric (→ {@code 4.13.2.redhat-00001}),
 * {@code -} when it is a qualifier (→ {@code 1.0.0.Final-redhat-00001}). This is best-effort: PNC
 * assigns the authoritative first-build version (and may OSGi-normalise the stem, e.g.
 * {@code 30.1-jre} → {@code 30.1.0.jre}); we do not normalise the stem here.</li>
 * </ul>
 */
public final class VersionIncrementer {

    /** DA's default rebuild suffix and zero-padding width (e.g. {@code redhat-00001}). */
    public static final String DEFAULT_SUFFIX = "redhat";
    public static final int DEFAULT_PADDING = 5;

    /**
     * Development-build markers stripped like {@code -SNAPSHOT} — dropped entirely, never carried into (or
     * re-appended after) the rebuild version. Comma-separated. Defaults to <em>empty</em> (no stripping):
     * a {@code -dev} tail is kept by default (→ {@code 10.1.0-dev-redhat-00001}). Opt in by configuring
     * markers, e.g. {@code dev} — the tail Tomcat appends to its in-development install version
     * ({@code 10.1.0-dev}) — to make it publish under the same version as the release path
     * ({@code 10.1.0.redhat-00001}).
     */
    public static final String DEFAULT_DEV_MARKERS = "";

    /** Matches a trailing {@code -SNAPSHOT}/{@code .SNAPSHOT} (case-insensitive), incl. its delimiter. */
    private static final Pattern SNAPSHOT_TAIL = Pattern.compile("(?i)[.-]?SNAPSHOT$");

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
     * @param preserveSnapshot PME's {@code versionSuffixSnapshot}: when {@code true}, a stripped
     *        {@code -SNAPSHOT} is re-appended at the very end (after the rebuild
     *        number), e.g. {@code 12.0.0-M1-redhat-00001-SNAPSHOT}; when {@code false}
     *        (PME default) SNAPSHOT is dropped entirely.
     * @param devMarkers comma-separated development markers to strip like SNAPSHOT (dropped, never
     *        re-appended); e.g. {@code dev} turns {@code 10.1.0-dev} into base {@code 10.1.0}. {@code null}
     *        or blank disables dev stripping.
     */
    public VersionIncrementer(String suffixBase, int padding, boolean preserveSnapshot, String devMarkers) {
        this.suffixBase = suffixBase;
        this.padding = padding > 0 ? padding : DEFAULT_PADDING;
        this.preserveSnapshot = preserveSnapshot;
        // Matches a trailing "<sep>redhat-<serial>", e.g. ".redhat-00002" or "-redhat-3".
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

    public static VersionIncrementer defaults() {
        return new VersionIncrementer(DEFAULT_SUFFIX, DEFAULT_PADDING);
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
     */
    public Result nextVersion(String baseVersion, String daLatest) {
        if (daLatest != null && !daLatest.trim().isEmpty()) {
            String latest = daLatest.trim();
            // Follow PME's removeSnapshot: never carry -SNAPSHOT through into the rebuild version.
            // Dev markers (e.g. -dev) are stripped the same way, but always dropped (never re-appended).
            boolean hadSnapshot = SNAPSHOT_TAIL.matcher(latest).find();
            String latestStem = stripDevTails(SNAPSHOT_TAIL.matcher(latest).replaceFirst(""));
            Matcher m = suffixTail.matcher(latestStem);
            if (m.find()) {
                String sep = m.group(1);
                String serialText = m.group(2);
                int next = Integer.parseInt(serialText) + 1;
                int width = Math.max(serialText.length(), padding);
                String stem = latestStem.substring(0, m.start());
                return new Result(
                        withSnapshot(stem + sep + suffixBase + "-" + pad(next, width), hadSnapshot),
                        false,
                        latest);
            }
            // DA returned a version without a recognisable redhat suffix: treat it as the stem for
            // the first serial rather than the declared base (DA's form is the authoritative one).
            return new Result(firstBuildOf(latestStem, hadSnapshot), true, latest);
        }
        String base = baseVersion == null ? "" : baseVersion.trim();
        boolean hadSnapshot = SNAPSHOT_TAIL.matcher(base).find();
        String baseStem = stripDevTails(SNAPSHOT_TAIL.matcher(base).replaceFirst(""));
        return new Result(firstBuildOf(baseStem, hadSnapshot), true, null);
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

    private String firstBuildOf(String stem, boolean hadSnapshot) {
        String sep = endsNumeric(stem) ? "." : "-";
        return withSnapshot(stem + sep + suffixBase + "-" + pad(1, padding), hadSnapshot);
    }

    /**
     * Re-applies a stripped {@code -SNAPSHOT} at the very end, PME-style, but only when
     * {@code preserveSnapshot} is set; otherwise SNAPSHOT stays dropped (PME's default).
     */
    private String withSnapshot(String version, boolean hadSnapshot) {
        return (preserveSnapshot && hadSnapshot) ? version + "-SNAPSHOT" : version;
    }

    /** True if the version's final {@code .}/{@code -}-delimited segment is purely numeric. */
    private static boolean endsNumeric(String version) {
        int i = Math.max(version.lastIndexOf('.'), version.lastIndexOf('-'));
        String tail = i < 0 ? version : version.substring(i + 1);
        if (tail.isEmpty()) {
            return false;
        }
        for (int c = 0; c < tail.length(); c++) {
            if (!Character.isDigit(tail.charAt(c))) {
                return false;
            }
        }
        return true;
    }

    private static String pad(int serial, int width) {
        return String.format("%0" + width + "d", serial);
    }
}
