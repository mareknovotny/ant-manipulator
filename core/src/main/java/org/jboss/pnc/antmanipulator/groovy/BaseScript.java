package org.jboss.pnc.antmanipulator.groovy;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.jboss.pnc.mavenmanipulator.io.rest.Translator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.Binding;
import groovy.lang.Script;

/**
 * Base class of every Ant Manipulation Groovy script, the analogue of PME's/GME's {@code BaseScript}.
 * Scripts obtain it with {@link AMEBaseScript} and call the API on the annotated variable, e.g.
 * {@code ame.getBaseDir()}.
 *
 * <p>
 * API (names match PME/GME where an equivalent exists):
 * <ul>
 * <li>{@link #getBaseDir()} — directory containing the root build file (the execution root);</li>
 * <li>{@link #getBuildFile()} — the root Ant build file being manipulated;</li>
 * <li>{@link #getUserProperties()} — the {@code -D} properties given on the command line;</li>
 * <li>{@link #getInvocationStage()} — the stage the script is currently running in;</li>
 * <li>{@link #getLogger()} — an SLF4J logger;</li>
 * <li>{@link #getRESTAPI()} — the configured Dependency Analyzer {@link Translator} (needs {@code -DrestURL});</li>
 * <li>{@link #getAlignedVersions()} — base version to computed publish version (only populated in
 * {@link InvocationStage#LAST}).</li>
 * </ul>
 * Note: use {@link #getUserProperties()} rather than {@code getProperties()}, which would hit Groovy's own
 * property API.
 */
public abstract class BaseScript extends Script {

    /** Required by Groovy's reflective script instantiation ({@code new Script(Binding)}). */
    protected BaseScript(Binding binding) {
        super(binding);
    }

    /** No-arg constructor required by some Groovy runtimes. */
    protected BaseScript() {
    }

    private File buildFile;
    private Properties userProperties = new Properties();
    private Translator translator;
    private Map<String, String> alignedVersions = Collections.emptyMap();
    private InvocationStage stage;
    private Logger logger;

    /** Called by {@link GroovyScriptRunner} before each run of the script. */
    void setValues(
            File buildFile,
            Properties userProperties,
            Translator translator,
            Map<String, String> alignedVersions,
            InvocationStage stage) {
        this.buildFile = buildFile;
        this.userProperties = userProperties;
        this.translator = translator;
        this.alignedVersions = alignedVersions;
        this.stage = stage;
    }

    public File getBuildFile() {
        return buildFile;
    }

    public File getBaseDir() {
        return buildFile.getParentFile();
    }

    public Properties getUserProperties() {
        return userProperties;
    }

    public InvocationStage getInvocationStage() {
        return stage;
    }

    public Map<String, String> getAlignedVersions() {
        return alignedVersions;
    }

    public Logger getLogger() {
        if (logger == null) {
            logger = LoggerFactory.getLogger(getClass());
        }
        return logger;
    }

    /**
     * @throws IllegalStateException if no {@code -DrestURL} was supplied, so there is no DA to talk to
     */
    public Translator getRESTAPI() {
        if (translator == null) {
            throw new IllegalStateException("No Dependency Analyzer is configured; set -DrestURL to use getRESTAPI().");
        }
        return translator;
    }
}
