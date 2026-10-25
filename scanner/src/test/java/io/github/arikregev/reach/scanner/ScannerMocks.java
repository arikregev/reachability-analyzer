package io.github.arikregev.reach.scanner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Mocks for the scan Job (D-034): an S3 stand-in that serves the presigned GETs and takes the summary PUT, and an
 * Artifactory stand-in (D-044). The Artifactory mock serves the fixture library {@code com.example.vuln:parser:1.0.0}
 * from local files and proxies every other path to Maven Central, like an Artifactory virtual repository with a
 * local and a remote member. Requests without the per-Job token get 401.
 */
public class ScannerMocks implements QuarkusTestResourceLifecycleManager {

    static final String TOKEN = "test-artifactory-token";
    static final String REPO = "/artifactory/maven-virtual";
    static WireMockServer s3;
    static WireMockServer artifactory;
    static Path secrets;
    static Path work;

    @Override
    public Map<String, String> start() {
        try {
            Path base = Files.createTempDirectory("reach-scanner-test");
            secrets = Files.createDirectories(base.resolve("secrets"));
            work = Files.createDirectories(base.resolve("work"));
            s3 = new WireMockServer(options().dynamicPort());
            s3.start();
            artifactory = new WireMockServer(withEgressProxy(options().dynamicPort()));
            artifactory.start();
            stubArtifactory(base);
            return Map.of(
                    "reach.scanner.secrets-dir", secrets.toString(),
                    "reach.scanner.work-dir", work.toString(),
                    "reach.scanner.deadline", "PT20M",
                    "reach.scanner.reserve", "PT30S",
                    "reach.scanner.image", "reach/scanner:test",
                    "reach.scanner.artifactory.url", artifactory.baseUrl() + "/artifactory");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The mock reaches Maven Central through the environment's HTTPS proxy, when there is one. */
    private static WireMockConfiguration withEgressProxy(WireMockConfiguration o) {
        String host = System.getProperty("https.proxyHost");
        String port = System.getProperty("https.proxyPort");
        return host == null || port == null ? o : o.proxyVia(host, Integer.parseInt(port));
    }

    private static void stubArtifactory(Path base) throws IOException {
        Path parserJar = parserJar(base);
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example.vuln</groupId>
                  <artifactId>parser</artifactId>
                  <version>1.0.0</version>
                </project>""";
        String dir = REPO + "/com/example/vuln/parser/1.0.0/";
        String auth = "Bearer " + TOKEN;
        artifactory.stubFor(any(urlPathMatching(".*")).atPriority(20)
                .willReturn(aResponse().withStatus(401).withBody("token required")));
        artifactory.stubFor(get(urlPathMatching(REPO + "/.*")).atPriority(10).withHeader("Authorization", equalTo(auth))
                .willReturn(aResponse().proxiedFrom("https://repo1.maven.org/maven2").withProxyUrlPrefixToRemove(REPO)));
        artifactory.stubFor(any(urlPathMatching(REPO + "/com/example/vuln/.*")).atPriority(5)
                .withHeader("Authorization", equalTo(auth)).willReturn(aResponse().withStatus(404)));
        artifactory.stubFor(get(urlPathEqualTo(dir + "parser-1.0.0.pom")).atPriority(1)
                .withHeader("Authorization", equalTo(auth)).willReturn(aResponse().withBody(pom)));
        artifactory.stubFor(get(urlPathEqualTo(dir + "parser-1.0.0.jar")).atPriority(1)
                .withHeader("Authorization", equalTo(auth)).willReturn(aResponse().withBody(Files.readAllBytes(parserJar))));
    }

    /** {@code com.example.vuln:parser:1.0.0}: {@code Parser.parse} is the vulnerable method, {@code safe} is not. */
    private static Path parserJar(Path base) throws IOException {
        Path src = Files.createDirectories(base.resolve("parser-src/com/example/vuln"));
        Files.writeString(src.resolve("Parser.java"), """
                package com.example.vuln;
                public class Parser {
                    public static String parse(String s) { return s.trim(); }
                    public static String safe(String s) { return s; }
                }""");
        Path classes = Files.createDirectories(base.resolve("parser-classes"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", classes.toString(),
                src.resolve("Parser.java").toString());
        if (rc != 0) {
            throw new IllegalStateException("parser fixture did not compile");
        }
        Path jar = base.resolve("parser-1.0.0.jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/maven/com.example.vuln/parser/pom.properties"));
            out.write("groupId=com.example.vuln\nartifactId=parser\nversion=1.0.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Override
    public void stop() {
        List.of(s3, artifactory).forEach(s -> {
            if (s != null) {
                s.stop();
            }
        });
    }
}
