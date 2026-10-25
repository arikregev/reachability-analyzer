package io.github.arikregev.reach.scanner;

import io.github.arikregev.reach.engine.Ecosystem;
import io.github.arikregev.reach.engine.ModuleDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Finds the build roots in the source tree, submodules included (architecture.md 8.2 step 1). A Maven root is a
 * directory with a {@code pom.xml} and no {@code pom.xml} above it, so a reactor is built once from its top. A Gradle
 * root is the topmost directory with a settings or build script. Where both tools claim the same directory, Maven
 * wins. Python, Go and JS roots are listed as well, so their findings are reported as waiting for their engine.
 * Symbolic links are not followed.
 */
final class ModuleDetector {

    private static final Set<String> SKIP = Set.of(".git", "node_modules", "target", "build", ".gradle", ".mvn",
            ".idea", "vendor", "__pycache__", ".venv", "venv", "dist");
    private static final int MAX_DEPTH = 12;

    private ModuleDetector() {
    }

    static List<ModuleDescriptor> detect(Path root) {
        List<ModuleDescriptor> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = root.relativize(dir).toString().replace('\\', '/');
                    if (has(dir, "pom.xml") && !claimed(out, rel, "maven")) {
                        out.add(new ModuleDescriptor(dir, rel, Ecosystem.MAVEN, "maven"));
                    } else if (has(dir, "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
                            && !claimed(out, rel, "gradle")) {
                        out.add(new ModuleDescriptor(dir, rel, Ecosystem.MAVEN, "gradle"));
                    }
                    if (has(dir, "pyproject.toml", "requirements.txt", "setup.py") && !claimed(out, rel, "python")) {
                        out.add(new ModuleDescriptor(dir, rel, Ecosystem.PYPI, "python"));
                    }
                    if (has(dir, "go.mod")) {
                        out.add(new ModuleDescriptor(dir, rel, Ecosystem.GOLANG, "go"));
                    }
                    if (has(dir, "package.json") && !claimed(out, rel, "npm")) {
                        out.add(new ModuleDescriptor(dir, rel, Ecosystem.NPM, "npm"));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(ModuleDescriptor::path).thenComparing(ModuleDescriptor::buildTool));
        return out;
    }

    private static boolean has(Path dir, String... names) {
        for (String n : names) {
            if (Files.isRegularFile(dir.resolve(n))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a root of the same tool family (Maven and Gradle count as one) already covers {@code rel}. */
    private static boolean claimed(List<ModuleDescriptor> found, String rel, String tool) {
        boolean jvm = tool.equals("maven") || tool.equals("gradle");
        return found.stream().anyMatch(m -> {
            boolean sameFamily = jvm ? m.ecosystem() == Ecosystem.MAVEN : m.buildTool().equals(tool);
            return sameFamily && (m.path().isEmpty() || rel.equals(m.path()) || rel.startsWith(m.path() + "/"));
        });
    }
}
