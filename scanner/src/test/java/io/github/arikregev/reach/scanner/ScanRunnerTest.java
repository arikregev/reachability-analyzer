package io.github.arikregev.reach.scanner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.arikregev.reach.contracts.ContractSchema;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The scan Job end to end against its mocks (D-034): the input bundle and a source tarball served as presigned
 * links, real Maven and Gradle builds whose dependencies come only through the Artifactory mock, the SootUp engine,
 * and the summary PUT, validated against {@code scan-summary.v1.schema.json}.
 */
@QuarkusTest
@QuarkusTestResource(ScannerMocks.class)
class ScanRunnerTest {

    static final String COMMIT = "4f1c9e2b7d3a8e6f0b5c1d2e3f4a5b6c7d8e9f0a";
    static final String PARSER = "pkg:maven/com.example.vuln/parser@1.0.0?type=jar";

    @Inject
    ScanRunner runner;

    @Inject
    ObjectMapper mapper;

    private final ContractSchema summarySchema = new ContractSchema(ContractSchema.SUMMARY);

    record Scan(UUID id, ObjectNode input) {
    }

    private Scan scan(String fixture) throws IOException, URISyntaxException, InterruptedException {
        UUID id = UUID.randomUUID();
        ObjectNode input = mapper.createObjectNode();
        input.put("schema", "reach.input.v1");
        input.put("scan_id", id.toString());
        input.put("attempt", 1);
        input.putObject("repo").put("full_name", "acme/" + fixture).put("url", "https://github.com/acme/" + fixture)
                .put("commit", COMMIT);
        input.putObject("dt_project").put("name", "acme/" + fixture).put("version", "main")
                .put("uuid", UUID.randomUUID().toString());
        input.putArray("findings");
        input.putNull("sbom");

        // The work dir is the Job's emptyDir: fresh per scan, except the tools' caches under home/ (faster tests).
        for (String dir : List.of("src", "builds")) {
            delete(ScannerMocks.work.resolve(dir));
        }
        Path tarball = Files.createTempFile("source", ".tar.zst");
        Path fixtureDir = Path.of(getClass().getResource("/fixtures/" + fixture).toURI());
        Process tar = new ProcessBuilder("tar", "--zstd", "-cf", tarball.toString(), "-C", fixtureDir.toString(), ".")
                .inheritIO().start();
        assertThat(tar.waitFor()).isZero();
        ScannerMocks.s3.stubFor(get(urlPathEqualTo("/" + id + "/source.tar.zst"))
                .willReturn(aResponse().withBody(Files.readAllBytes(tarball))));
        ScannerMocks.s3.stubFor(put(urlPathEqualTo("/" + id + "/summary.json")).willReturn(aResponse().withStatus(200)));
        String base = ScannerMocks.s3.baseUrl() + "/" + id + "/";
        Files.writeString(ScannerMocks.secrets.resolve("source-get-url"), base + "source.tar.zst?X-Amz-Signature=x");
        Files.writeString(ScannerMocks.secrets.resolve("input-get-url"), base + "input.json?X-Amz-Signature=x");
        Files.writeString(ScannerMocks.secrets.resolve("summary-put-url"), base + "summary.json?X-Amz-Signature=x");
        Files.writeString(ScannerMocks.secrets.resolve("artifactory-token"), ScannerMocks.TOKEN + "\n");
        return new Scan(id, input);
    }

    private ObjectNode finding(Scan scan, String purl, String... methods) {
        ObjectNode f = ((ArrayNode) scan.input().get("findings")).addObject();
        f.put("component_uuid", UUID.randomUUID().toString());
        f.put("vulnerability_uuid", UUID.randomUUID().toString());
        f.put("vuln_id", "CVE-2026-" + (1000 + scan.input().get("findings").size()));
        f.put("purl", purl);
        ArrayNode symbols = f.putArray("symbols");
        for (String m : methods) {
            symbols.addObject().put("symbol", m).put("kind", "METHOD");
        }
        return f;
    }

    private JsonNode run(Scan scan) throws IOException {
        ScannerMocks.s3.stubFor(get(urlPathEqualTo("/" + scan.id() + "/input.json"))
                .willReturn(aResponse().withBody(mapper.writeValueAsBytes(scan.input()))));
        assertThat(runner.run()).isEqualTo(ScanRunner.EXIT_OK);
        var puts = ScannerMocks.s3.findAll(putRequestedFor(urlPathEqualTo("/" + scan.id() + "/summary.json")));
        assertThat(puts).hasSize(1);
        JsonNode summary = mapper.readTree(puts.getFirst().getBody());
        assertThat(summarySchema.validate(summary)).isEmpty();
        return summary;
    }

    private static JsonNode verdictOf(JsonNode summary, JsonNode finding) {
        for (JsonNode f : summary.get("findings")) {
            if (f.get("vulnerability_uuid").asText().equals(finding.get("vulnerability_uuid").asText())) {
                return f;
            }
        }
        throw new AssertionError("no verdict for " + finding);
    }

    @Test
    void mavenProjectGetsSymbolLevelVerdictsThroughArtifactory() throws Exception {
        Scan scan = scan("maven-app");
        ObjectNode reached = finding(scan, PARSER, "com.example.vuln.Parser.parse");
        ObjectNode notReached = finding(scan, PARSER, "com.example.vuln.Parser.safe");
        ObjectNode unresolved = finding(scan, "pkg:maven/org.other/absent@1.0");
        ObjectNode npm = finding(scan, "pkg:npm/left-pad@1.3.0");

        JsonNode summary = run(scan);

        assertThat(summary.get("complete").asBoolean()).isTrue();
        assertThat(summary.get("scanner").get("image").asText()).isEqualTo("reach/scanner:test");
        JsonNode module = summary.get("modules").get(0);
        assertThat(module.get("status").asText()).as(module.toString()).isEqualTo("ANALYSED");
        assertThat(module.get("build_tool").asText()).isEqualTo("maven");
        assertThat(module.get("toolchain").asText()).contains("Apache Maven");

        JsonNode r = verdictOf(summary, reached);
        assertThat(r.get("verdict").asText()).isEqualTo("REACHABLE");
        assertThat(r.get("level").asText()).isEqualTo("SYMBOL");
        assertThat(r.get("engine").asText()).isEqualTo("sootup");
        JsonNode frames = r.get("call_paths").get(0).get("frames");
        assertThat(frames.get(frames.size() - 1).get("symbol").asText()).isEqualTo("com.example.vuln.Parser.parse(java.lang.String)");
        assertThat(verdictOf(summary, notReached).get("verdict").asText()).isEqualTo("NOT_REACHABLE");
        assertThat(verdictOf(summary, unresolved).get("reason").asText()).isEqualTo("COMPONENT_NOT_RESOLVED");
        assertThat(verdictOf(summary, npm).get("reason").asText()).isEqualTo("UNSUPPORTED_LANGUAGE");

        assertArtifactoryOnlySawTheToken();
    }

    @Test
    void gradleProjectIsBuiltWithTheMirrorInsteadOfItsRepositories() throws Exception {
        Scan scan = scan("gradle-app");
        ObjectNode safe = finding(scan, PARSER, "com.example.vuln.Parser.safe");
        ObjectNode parse = finding(scan, PARSER, "com.example.vuln.Parser.parse");

        JsonNode summary = run(scan);

        JsonNode module = summary.get("modules").get(0);
        assertThat(module.get("status").asText()).as(module.toString()).isEqualTo("ANALYSED");
        assertThat(module.get("build_tool").asText()).isEqualTo("gradle");
        assertThat(verdictOf(summary, safe).get("verdict").asText()).isEqualTo("REACHABLE");
        assertThat(verdictOf(summary, parse).get("verdict").asText()).isEqualTo("NOT_REACHABLE");
        assertArtifactoryOnlySawTheToken();
    }

    @Test
    void aModuleThatDoesNotBuildGivesUnknownNotAFailedJob() throws Exception {
        Scan scan = scan("maven-broken");
        ObjectNode f = finding(scan, PARSER, "com.example.vuln.Parser.parse");

        JsonNode summary = run(scan);

        JsonNode module = summary.get("modules").get(0);
        assertThat(module.get("status").asText()).isEqualTo("BUILD_FAILED");
        assertThat(module.get("diagnostics").get(0).asText()).contains("mvn exited with 1");
        JsonNode v = verdictOf(summary, f);
        assertThat(v.get("verdict").asText()).isEqualTo("UNKNOWN");
        assertThat(v.get("reason").asText()).isEqualTo("BUILD_FAILED");
    }

    @Test
    void anInputBundleForAnotherScanFailsTheJob() throws Exception {
        Scan scan = scan("maven-app");
        scan.input().put("schema", "reach.input.v0");
        ScannerMocks.s3.stubFor(get(urlPathEqualTo("/" + scan.id() + "/input.json"))
                .willReturn(aResponse().withBody(mapper.writeValueAsBytes(scan.input()))));

        assertThat(runner.run()).isEqualTo(ScanRunner.EXIT_INFRA);
        assertThat(ScannerMocks.s3.findAll(putRequestedFor(urlPathEqualTo("/" + scan.id() + "/summary.json")))).isEmpty();
    }

    @Test
    void anS3OutageExitsWithTheRetryableCode() throws Exception {
        Scan scan = scan("maven-app");
        ScannerMocks.s3.stubFor(get(urlPathEqualTo("/" + scan.id() + "/input.json"))
                .willReturn(aResponse().withBody(mapper.writeValueAsBytes(scan.input()))));
        ScannerMocks.s3.stubFor(get(urlPathEqualTo("/" + scan.id() + "/source.tar.zst"))
                .willReturn(aResponse().withStatus(503)));

        assertThat(runner.run()).isEqualTo(ScanRunner.EXIT_TRANSFER);
        assertThat(ScannerMocks.s3.findAll(getRequestedFor(urlPathEqualTo("/" + scan.id() + "/source.tar.zst")))).hasSize(4);
    }

    private static void assertArtifactoryOnlySawTheToken() {
        var requests = ScannerMocks.artifactory.getAllServeEvents();
        assertThat(requests).isNotEmpty();
        assertThat(requests).allSatisfy(e -> assertThat(e.getRequest().getHeader("Authorization"))
                .as(e.getRequest().getUrl()).isEqualTo("Bearer " + ScannerMocks.TOKEN));
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
