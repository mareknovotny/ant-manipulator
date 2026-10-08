package org.jboss.pnc.antmanipulator.cli;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.apache.tools.ant.Location;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.Target;

/**
 * Reads an Ant build file into an effective {@link Project} model using Ant's own parser
 * ({@link ProjectHelper}). This is the Ant analogue of how pom-manipulation-ext reuses Maven's
 * model reader instead of hand-rolling XML parsing.
 *
 * <p>
 * Reusing Ant's parser means {@code <import>}/{@code <include>} are resolved, properties are
 * expanded and macrodefs/targets are registered exactly as a real Ant run would see them. Because
 * every {@link Target} records the {@link Location} (and therefore the file) it came from, we can
 * recover the set of build files that were pulled in — the first ingredient of a build-file
 * hierarchy crawl (the analogue of {@code PomIO.peekAtPomHierarchy}).
 *
 * <p>
 * Caveat: {@code ProjectHelper} executes top-level tasks at parse time (that is how {@code import}
 * works). A build file whose top-level tasks depend on an unavailable antlib will therefore fail to
 * parse; a later formatting-preserving raw-XML reader will be needed for the rewrite step anyway.
 */
public class BuildFilePeek {

    private final File buildFile;
    private final Project project;
    private final Set<File> involvedBuildFiles;

    private BuildFilePeek(File buildFile, Project project, Set<File> involvedBuildFiles) {
        this.buildFile = buildFile;
        this.project = project;
        this.involvedBuildFiles = involvedBuildFiles;
    }

    public static BuildFilePeek peek(File buildFile) {
        Project project = new Project();
        project.init();
        // configureProject sets ant.file and delegates to the registered ProjectHelper, resolving
        // imports and populating targets.
        ProjectHelper.configureProject(project, buildFile);

        Set<File> involved = new LinkedHashSet<>();
        involved.add(buildFile.getAbsoluteFile());
        for (Target target : project.getTargets().values()) {
            Location loc = target.getLocation();
            if (loc != null && loc.getFileName() != null) {
                involved.add(new File(loc.getFileName()).getAbsoluteFile());
            }
        }

        return new BuildFilePeek(buildFile, project, involved);
    }

    public File getBuildFile() {
        return buildFile;
    }

    public String getProjectName() {
        return project.getName();
    }

    public String getDefaultTarget() {
        return project.getDefaultTarget();
    }

    /** Target names declared across the main file and everything it imported. */
    public Set<String> getTargetNames() {
        Set<String> names = new TreeSet<>(project.getTargets().keySet());
        names.remove(""); // Ant registers an unnamed implicit top-level "target"
        return names;
    }

    /** All build files that contributed targets: the main file plus any imported/included files. */
    public Set<File> getInvolvedBuildFiles() {
        return involvedBuildFiles;
    }
}
