package io.github.arikregev.reach.engine.java;

import io.github.arikregev.reach.engine.BuildEnv;
import io.github.arikregev.reach.engine.BuildException;
import io.github.arikregev.reach.engine.ModuleDescriptor;
import io.github.arikregev.reach.engine.PreparedModule;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a Maven reactor or a Gradle build with the image's tools, never a wrapper, and reads back each subproject's
 * classes and runtime classpath (architecture.md 7.1, D-011). Dependencies come only from the Artifactory mirror;
 * the token reaches the tools through the environment, never through a file.
 */
final class JavaBuild {

    static final String MAVEN = "maven";
    static final String GRADLE = "gradle";
    static final String CLASSPATH_FILE = "reach-classpath.txt";
    static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath";
    static final String TOKEN_ENV = "REACH_ARTIFACTORY_TOKEN";

    private JavaBuild() {
    }

    static PreparedModule prepare(ModuleDescriptor module, BuildEnv env) throws BuildException {
        return switch (module.buildTool()) {
            case MAVEN -> maven(module, env);
            case GRADLE -> gradle(module, env);
            default -> throw new BuildException("unsupported build tool " + module.buildTool());
        };
    }

    private static PreparedModule maven(ModuleDescriptor module, BuildEnv env) throws BuildException {
        Path work = workDir(module, env);
        Map<String, String> processEnv = processEnv(env);
        List<String> command = new ArrayList<>(List.of("mvn", "-B", "-ntp", "-e",
                "-Dmaven.repo.local=" + env.home().resolve(".m2/repository"),
                "-DskipTests", "-Dmaven.javadoc.skip=true", "-Dgpg.skip=true",
                "-Dmaven.source.skip=true"));
        env.repositoryUrl(MAVEN).ifPresent(url -> {
            Path settings = work.resolve("settings.xml");
            write(settings, mavenSettings(url));
            command.addAll(List.of("-s", settings.toString(), "-gs", settings.toString()));
        });
        command.addAll(List.of("package", DEPENDENCY_PLUGIN, "-DincludeScope=runtime",
                "-Dmdep.outputFile=target/" + CLASSPATH_FILE));
        Processes.run(command, module.dir(), processEnv, work.resolve("build.log"), env.buildTimeout());
        List<PreparedModule.Unit> units = new ArrayList<>();
        for (Path file : find(module.dir(), "target", CLASSPATH_FILE)) {
            Path project = file.getParent().getParent();
            Path classes = project.resolve("target/classes");
            List<Path> entries = split(read(file));
            units.add(unit(module, project, Files.isDirectory(classes) ? List.of(classes) : List.of(), entries));
        }
        return prepared(module, units, Processes.firstLine(List.of("mvn", "-v"), processEnv));
    }

    private static PreparedModule gradle(ModuleDescriptor module, BuildEnv env) throws BuildException {
        Path work = workDir(module, env);
        Map<String, String> processEnv = processEnv(env);
        processEnv.put("GRADLE_USER_HOME", env.home().resolve(".gradle").toString());
        env.repositoryUrl(GRADLE).ifPresent(url -> processEnv.put("REACH_GRADLE_MIRROR", url));
        Path init = work.resolve("reach-init.gradle");
        try (InputStream in = JavaBuild.class.getResourceAsStream("/reach/reach-init.gradle")) {
            Files.write(init, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> command = List.of("gradle", "--init-script", init.toString(), "--no-daemon", "--console=plain",
                "--no-configuration-cache", "--no-build-cache", "-x", "test", "reachClasspath");
        Processes.run(command, module.dir(), processEnv, work.resolve("build.log"), env.buildTimeout());
        List<PreparedModule.Unit> units = new ArrayList<>();
        for (Path file : find(module.dir(), "build", CLASSPATH_FILE)) {
            Path project = file.getParent().getParent();
            Map<String, String> lines = new HashMap<>();
            read(file).lines().forEach(l -> {
                int eq = l.indexOf('=');
                if (eq > 0) {
                    lines.put(l.substring(0, eq), l.substring(eq + 1));
                }
            });
            units.add(unit(module, project, split(lines.getOrDefault("app", "")), split(lines.getOrDefault("deps", ""))));
        }
        return prepared(module, units, Processes.firstLine(List.of("gradle", "--version"), processEnv));
    }

    private static PreparedModule prepared(ModuleDescriptor module, List<PreparedModule.Unit> units, String toolchain)
            throws BuildException {
        if (units.isEmpty()) {
            throw new BuildException("the build produced no Java classpath (no Java subproject?)");
        }
        String jdk = "Java " + Runtime.version().feature();
        return new PreparedModule(module, toolchain.isEmpty() ? jdk : toolchain + "; " + jdk, units, List.of());
    }

    /** Classpath entries inside the repository are the application's own code (sibling subprojects). */
    private static PreparedModule.Unit unit(ModuleDescriptor module, Path project, List<Path> app, List<Path> entries) {
        Path root = module.dir().toAbsolutePath().normalize();
        List<Path> appRoots = new ArrayList<>(app);
        List<Path> deps = new ArrayList<>();
        for (Path e : entries) {
            Path abs = e.toAbsolutePath().normalize();
            if (!Files.exists(abs) || appRoots.contains(abs)) {
                continue;
            }
            (abs.startsWith(root) ? appRoots : deps).add(abs);
        }
        String name = root.relativize(project.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return new PreparedModule.Unit(name.isEmpty() ? "." : name, appRoots, deps);
    }

    private static String mavenSettings(String url) {
        return """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <mirrors>
                    <mirror>
                      <id>reach-artifactory</id>
                      <mirrorOf>*</mirrorOf>
                      <url>%s</url>
                    </mirror>
                  </mirrors>
                  <servers>
                    <server>
                      <id>reach-artifactory</id>
                      <configuration>
                        <httpHeaders>
                          <property>
                            <name>Authorization</name>
                            <value>Bearer ${env.%s}</value>
                          </property>
                        </httpHeaders>
                      </configuration>
                    </server>
                  </servers>
                </settings>
                """.formatted(xml(url), TOKEN_ENV);
    }

    private static Map<String, String> processEnv(BuildEnv env) {
        Map<String, String> m = new HashMap<>(env.environment());
        m.put("HOME", env.home().toString());
        m.put("MAVEN_ARGS", "");
        env.artifactoryToken().ifPresent(t -> m.put(TOKEN_ENV, t));
        return m;
    }

    private static Path workDir(ModuleDescriptor module, BuildEnv env) {
        String name = module.path().isEmpty() ? "_root" : module.path().replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = env.workDir().resolve("builds").resolve(module.buildTool() + "-" + name);
        try {
            Files.createDirectories(dir);
            Files.createDirectories(env.home());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    private static List<Path> find(Path root, String outputDir, String fileName) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals(fileName)
                            && p.getParent() != null && p.getParent().getFileName().toString().equals(outputDir))
                    .filter(p -> !p.toString().contains(File.separator + "node_modules" + File.separator))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> split(String classpath) {
        return Arrays.stream(classpath.strip().split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String content) {
        try {
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
