package org.jboss.pnc.antmanipulator.gav;

import java.io.File;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands Ant {@code ${...}} property references using Ant's own property engine.
 *
 * <p>
 * Ant versions are routinely tokenised (Ant's own {@code version.txt} is literally
 * {@code VERSION=${project.version}}, and pom templates carry {@code ${pom.version}}). Because we
 * already depend on Ant, we let {@link Project} evaluate the build file's {@code <property>} tasks and
 * imported property files, then use {@link Project#replaceProperties(String)} to resolve tokens —
 * rather than re-implementing Ant's property precedence ourselves.
 *
 * <p>
 * Parsing the root build file executes top-level tasks (that is how {@code <property file=...>} and
 * {@code <import>} take effect). If that fails — e.g. a missing antlib — we degrade gracefully to an
 * empty project, in which case unknown {@code ${x}} tokens are returned unchanged.
 */
public class AntPropertyResolver {

    private static final Logger logger = LoggerFactory.getLogger(AntPropertyResolver.class);

    private final Project project;
    private final boolean fullyLoaded;

    private AntPropertyResolver(Project project, boolean fullyLoaded) {
        this.project = project;
        this.fullyLoaded = fullyLoaded;
    }

    public static AntPropertyResolver forRoot(File rootBuildFile) {
        Project project = new Project();
        project.init();
        boolean loaded = false;
        try {
            ProjectHelper.configureProject(project, rootBuildFile);
            loaded = true;
        } catch (Exception e) {
            logger.warn(
                    "Could not fully evaluate properties from {} ({}); ${{...}} tokens may stay unresolved.",
                    rootBuildFile,
                    e.getMessage());
        }
        return new AntPropertyResolver(project, loaded);
    }

    /** True if the root build file was parsed successfully and properties are available. */
    public boolean isFullyLoaded() {
        return fullyLoaded;
    }

    /** The raw value of a single property, or {@code null} if undefined. */
    public String getProperty(String name) {
        return project.getProperty(name);
    }

    /**
     * Expand {@code ${...}} references in {@code value}. Unknown properties are left untouched.
     * Returns {@code value} unchanged if it is {@code null} or contains no tokens.
     */
    public String expand(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        try {
            return project.replaceProperties(value);
        } catch (Exception e) {
            logger.debug("Failed to expand '{}': {}", value, e.getMessage());
            return value;
        }
    }
}
