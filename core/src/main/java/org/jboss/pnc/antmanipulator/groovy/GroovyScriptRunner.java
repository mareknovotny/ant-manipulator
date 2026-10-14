package org.jboss.pnc.antmanipulator.groovy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jboss.pnc.mavenmanipulator.io.rest.Translator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * Loads and runs the user-supplied Groovy scripts named by {@code -DgroovyScripts=...}, the analogue of
 * pom-manipulation-ext's {@code BaseGroovyManipulator}.
 *
 * <p>
 * The property is a comma-separated list of {@code https://} URLs, {@code file://} URLs or plain file
 * paths. Plain {@code http://} is rejected: scripts are executable code, so they must not be fetched over
 * an unauthenticated, unencrypted channel. Scripts run in the order listed.
 *
 * <p>
 * Each script must be annotated with {@link InvocationPoint} and {@link AMEBaseScript}; it is run only
 * when its declared stage is the current stage ({@link InvocationStage#ALL} matches every stage,
 * {@link InvocationStage#PREPARSE} matches {@link InvocationStage#FIRST}).
 */
public final class GroovyScriptRunner {

    /** The {@code -D} property naming the scripts; matches PME/GME verbatim. */
    public static final String PROP_GROOVY_SCRIPTS = "groovyScripts";

    private static final Logger logger = LoggerFactory.getLogger(GroovyScriptRunner.class);

    private final List<String> locations;
    private final File buildFile;
    private final Properties userProperties;
    private final Translator translator;
    /** Parsed once per location and reused across every {@link #run} call, however many stages invoke it. */
    private final Map<String, Script> parsedScripts = new LinkedHashMap<>();

    /**
     * @param groovyScripts the raw {@code groovyScripts} value (may be null/blank, meaning no scripts)
     * @param buildFile the root Ant build file
     * @param userProperties the {@code -D} properties, exposed to scripts
     * @param translator the DA client exposed to scripts, or null when no {@code restURL} is configured
     */
    public GroovyScriptRunner(
            String groovyScripts,
            File buildFile,
            Properties userProperties,
            Translator translator) {
        this.locations = parse(groovyScripts);
        this.buildFile = buildFile;
        this.userProperties = userProperties;
        this.translator = translator;
    }

    /** True if at least one script was configured. */
    public boolean hasScripts() {
        return !locations.isEmpty();
    }

    /**
     * Run every configured script whose {@link InvocationPoint} matches {@code stage}.
     *
     * @param alignedVersions base version to computed publish version, exposed to the script (may be empty)
     * @throws IllegalStateException if a script cannot be loaded or fails; the run should be aborted, as in PME
     */
    public void run(InvocationStage stage, Map<String, String> alignedVersions) {
        for (String location : locations) {
            runOne(
                    location,
                    stage,
                    alignedVersions == null ? Collections.<String, String> emptyMap() : alignedVersions);
        }
    }

    private void runOne(String location, InvocationStage stage, Map<String, String> alignedVersions) {
        Script script = parsedScripts.computeIfAbsent(location, this::parseScript);

        InvocationStage declared = script.getClass().getAnnotation(InvocationPoint.class).invocationPoint();
        InvocationStage effectiveDeclared = declared == InvocationStage.PREPARSE ? InvocationStage.FIRST : declared;
        if (effectiveDeclared != InvocationStage.ALL && effectiveDeclared != stage) {
            logger.debug(
                    "Ignoring script {} as invocation point {} does not match stage {}",
                    location,
                    declared,
                    stage);
            return;
        }

        ((BaseScript) script).setValues(buildFile, userProperties, translator, alignedVersions, stage);
        logger.info("Executing {} at stage {}", location, stage);
        try {
            script.run();
        } catch (Exception e) {
            // Groovy does not enforce checked exceptions, so a script body can throw one without
            // declaring it; catching Exception (not just RuntimeException) ensures it is still wrapped
            // and aborts the run, rather than escaping uncaught past Cli's exit-code contract.
            throw new IllegalStateException("Problem running Groovy script " + location, e);
        }
        logger.info("Completed {}", location);
    }

    /**
     * Parses and validates a script, fetching it over the network for a {@code https://} location. Called at
     * most once per location (see {@link #parsedScripts}), however many stages end up running it.
     */
    private Script parseScript(String location) {
        Script script;
        try {
            script = new GroovyShell().parse(toUri(location));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to parse Groovy script " + location, e);
        }

        if (!(script instanceof BaseScript)) {
            throw new IllegalStateException(
                    "Groovy script " + location + " must declare '@AMEBaseScript BaseScript <name>'");
        }
        if (script.getClass().getAnnotation(InvocationPoint.class) == null) {
            throw new IllegalStateException(
                    "Groovy script " + location + " must declare '@InvocationPoint(invocationPoint = ...)'");
        }
        return script;
    }

    static List<String> parse(String groovyScripts) {
        List<String> out = new ArrayList<>();
        if (groovyScripts == null) {
            return out;
        }
        for (String s : groovyScripts.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Resolve a configured location to a URI, rejecting insecure schemes. */
    static URI toUri(String location) throws IOException {
        String lower = location.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) {
            throw new IOException("Refusing to load Groovy script over plain HTTP (use https://): " + location);
        }
        if (lower.startsWith("https://") || lower.startsWith("file:")) {
            return URI.create(location);
        }
        File f = new File(location);
        if (!f.isFile()) {
            throw new IOException("Groovy script not found: " + location);
        }
        return f.toURI();
    }
}
