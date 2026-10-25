package io.github.arikregev.reach.engine.java;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * A {@code pkg:maven/<group>/<artifact>@<version>} purl, and whether a resolved classpath entry is that artifact
 * (architecture.md 8.2 step 3). A jar matches by its path in the Maven local repository
 * ({@code <group dirs>/<artifact>/<version>/}) or the Gradle cache ({@code <group>/<artifact>/<version>/}), and
 * otherwise by the {@code META-INF/maven/<group>/<artifact>/pom.properties} it carries.
 */
record MavenPurl(String group, String artifact, String version) {

    static Optional<MavenPurl> parse(String purl) {
        if (purl == null || !purl.startsWith("pkg:maven/")) {
            return Optional.empty();
        }
        String rest = purl.substring("pkg:maven/".length());
        int cut = indexOfAny(rest, '?', '#');
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        int at = rest.lastIndexOf('@');
        String version = at < 0 ? null : decode(rest.substring(at + 1));
        String path = at < 0 ? rest : rest.substring(0, at);
        int slash = path.lastIndexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new MavenPurl(decode(path.substring(0, slash)), decode(path.substring(slash + 1)), version));
    }

    boolean matches(Path entry) {
        String p = entry.toAbsolutePath().normalize().toString().replace('\\', '/');
        String v = version == null ? "" : version + "/";
        if (p.contains("/" + group.replace('.', '/') + "/" + artifact + "/" + v)
                || p.contains("/" + group + "/" + artifact + "/" + v)) {
            return true;
        }
        return Files.isRegularFile(entry) && p.endsWith(".jar") && hasPomProperties(entry);
    }

    private boolean hasPomProperties(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/maven/" + group + "/" + artifact + "/pom.properties");
            if (entry == null) {
                return false;
            }
            if (version == null) {
                return true;
            }
            var props = new java.util.Properties();
            try (var in = zip.getInputStream(entry)) {
                props.load(in);
            }
            return version.equals(props.getProperty("version"));
        } catch (IOException e) {
            return false;
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
