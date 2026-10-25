package io.github.arikregev.reach.engine.java;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles small Java sources at test time (release 25, so the engine reads current class files). */
final class Fixtures {

    private Fixtures() {
    }

    /** Compiles {@code sources} (path to content) into {@code out}, with {@code classpath} available. */
    static Path compile(Path out, Map<String, String> sources, List<Path> classpath) {
        try {
            Path src = Files.createTempDirectory(out.getParent(), "src");
            List<String> files = new ArrayList<>();
            for (var e : sources.entrySet()) {
                Path f = src.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue());
                files.add(f.toString());
            }
            Files.createDirectories(out);
            List<String> args = new ArrayList<>(List.of("--release", "25", "-g", "-d", out.toString()));
            if (!classpath.isEmpty()) {
                args.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList())));
            }
            args.addAll(files);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            int rc = javac.run(null, null, null, args.toArray(String[]::new));
            if (rc != 0) {
                throw new IllegalStateException("fixture did not compile");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Jars a classes directory, with the pom.properties Maven puts into every jar it builds. */
    static Path jar(Path classes, Path jar, String group, String artifact, String version) {
        try {
            Files.createDirectories(jar.getParent());
            try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os);
                 Stream<Path> walk = Files.walk(classes)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(p));
                    out.closeEntry();
                }
                if (group != null) {
                    out.putNextEntry(new JarEntry("META-INF/maven/" + group + "/" + artifact + "/pom.properties"));
                    out.write(("groupId=" + group + "\nartifactId=" + artifact + "\nversion=" + version + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
