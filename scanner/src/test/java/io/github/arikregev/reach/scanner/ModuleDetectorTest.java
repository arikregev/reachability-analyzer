package io.github.arikregev.reach.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.arikregev.reach.engine.ModuleDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDetectorTest {

    @TempDir
    Path root;

    private void touch(String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "");
    }

    @Test
    void findsTopmostBuildRootsPerToolFamily() throws IOException {
        touch("pom.xml");
        touch("core/pom.xml");
        touch("tools/build.gradle");
        touch("libs/sub/settings.gradle");
        touch("web/package.json");
        touch("web/node_modules/x/package.json");
        touch("svc/go.mod");
        touch("svc/inner/go.mod");
        touch("py/pyproject.toml");
        touch("py/requirements.txt");
        touch("target/pom.xml");

        List<String> found = ModuleDetector.detect(root).stream().map(m -> m.path() + ":" + m.buildTool()).toList();

        // The root reactor covers core/ and the Gradle builds below it; Go modules nest independently.
        assertThat(found).containsExactly(":maven", "py:python", "svc:go", "svc/inner:go", "web:npm");
    }

    @Test
    void gradleBuildsWithoutMaven() throws IOException {
        touch("a/settings.gradle.kts");
        touch("a/app/build.gradle.kts");
        touch("b/build.gradle");

        List<ModuleDescriptor> found = ModuleDetector.detect(root);

        assertThat(found).extracting(ModuleDescriptor::path).containsExactly("a", "b");
        assertThat(found).allSatisfy(m -> assertThat(m.buildTool()).isEqualTo("gradle"));
    }
}
