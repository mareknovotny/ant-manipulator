package org.jboss.pnc.antmanipulator.groovy;

/**
 * Controls when a Groovy script runs relative to the manipulation pipeline.
 *
 * <p>
 * Mirrors {@code org.jboss.pnc.mavenmanipulator.core.groovy.InvocationStage} from pom-manipulation-ext so
 * that scripts which already target PME or GME can be annotated with the same vocabulary.
 *
 * <ul>
 * <li>{@link #FIRST} — runs before the Ant build files are read for GAV resolution. Safe to modify files
 * on disk at this point.</li>
 * <li>{@link #PREPARSE} — alias for {@link #FIRST}, kept for parity with GME's vocabulary; this tool has
 * no separate pre-parse step for the alias to distinguish.</li>
 * <li>{@link #LAST} — runs after the versions have been aligned and rewritten. The in-memory state
 * exposed by the script API reflects the final computed versions.</li>
 * <li>{@link #ALL} — runs at both {@code FIRST} and {@code LAST}; use {@link BaseScript#getInvocationStage()}
 * inside the script body to tell them apart.</li>
 * </ul>
 */
public enum InvocationStage {
    FIRST,
    PREPARSE,
    LAST,
    ALL
}
