package org.jboss.pnc.antmanipulator.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Copies a fixture Ant project from the test classpath into a fresh directory (typically a {@code @TempDir}). */
final class FixtureCopier {

    private FixtureCopier() {
    }

    /** Copies a classpath resource directory (as laid out under {@code src/test/resources}) into {@code target}. */
    static void copyResourceDir(String resourceName, Path target) throws IOException, URISyntaxException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL url = cl.getResource(resourceName);
        assertThat(url).as("test fixture '%s' on the classpath", resourceName).isNotNull();
        Path source = Paths.get(url.toURI());

        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
