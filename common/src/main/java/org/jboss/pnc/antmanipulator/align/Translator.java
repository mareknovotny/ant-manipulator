package org.jboss.pnc.antmanipulator.align;

import java.util.List;
import java.util.Map;

/**
 * Looks up the version each coordinate should be aligned to — the analogue of PME's
 * {@code org.jboss.pnc.mavenmanipulator.io.rest.Translator}, which delegates alignment to an external
 * source of truth (the Dependency Analyzer, "DA") rather than deciding versions locally.
 *
 * <p>
 * Given a list of concrete {@link Gav}s, the service returns a map from each input coordinate to
 * the version it should become (DA's {@code bestMatchVersion}, e.g. a rebuilt {@code -redhat-00003}
 * build). Coordinates the service has no answer for are simply absent from the returned map.
 */
public interface Translator {

    /**
     * Look up aligned versions honouring suffix priority (DA's {@code lookup/maven}).
     *
     * @param coordinates the concrete coordinates to align
     * @return map from each input coordinate to its aligned version (missing entries = no match)
     */
    Map<Gav, String> lookupVersions(List<Gav> coordinates);

    /**
     * Look up the latest version for the configured suffix mode, ignoring suffix priority
     * (DA's {@code lookup/maven/latest}). Typically used for project-version lookups.
     */
    Map<Gav, String> lookupProjectVersions(List<Gav> coordinates);
}
