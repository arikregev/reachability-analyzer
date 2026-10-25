package io.github.arikregev.reach.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScanRequestParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ScanRequestParser parser = new ScanRequestParser();

    private static ObjectNode example() throws IOException {
        try (InputStream in = ScanRequestParserTest.class.getResourceAsStream("/contracts/scan-request.v1.example.json")) {
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    private ParseResult parse(ObjectNode node) throws IOException {
        return parser.parse(MAPPER.writeValueAsBytes(node));
    }

    private static ObjectNode reach(ObjectNode root) {
        return (ObjectNode) root.at("/unmapped/reachability");
    }

    @Test
    void publishedExampleIsValid() throws IOException {
        ParseResult result = parse(example());

        assertThat(result).isInstanceOf(ParseResult.Valid.class);
        ScanRequest r = ((ParseResult.Valid) result).request();
        assertThat(r.requestUid()).isNotBlank();
        assertThat(r.commit()).matches("[0-9a-f]{40}");
        assertThat(r.dtProjectName()).isNotBlank();
        assertThat(r.dtProjectVersion()).isNotBlank();
    }

    @Test
    void nulCharactersAreSchemaInvalid() throws IOException {
        ObjectNode root = example();
        ((ObjectNode) root.get("metadata")).put("uid", "a\0b");
        ((ObjectNode) root.get("unmapped")).putObject("extra").put("k\0", "v");

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.code()).isEqualTo(ParseResult.ErrorCode.SCHEMA_INVALID);
        assertThat(invalid.violations()).extracting(Violation::pointer)
                .contains("/metadata/uid", "/unmapped/extra/k\0");
    }

    @Test
    void linkTtlThatOverflowsDurationIsSchemaInvalid() throws IOException {
        ObjectNode root = example();
        reach(root).put("link_ttl", "P999999999999999D");

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.code()).isEqualTo(ParseResult.ErrorCode.SCHEMA_INVALID);
        assertThat(invalid.violations()).extracting(Violation::pointer).containsExactly("/unmapped/reachability/link_ttl");
    }

    @Test
    void mapsOptionalFields() throws IOException {
        ObjectNode root = example();
        reach(root).putArray("formats").add("openvex").add("ocsf");
        reach(root).put("dt_write_back", "VEX");
        reach(root).put("link_ttl", "P2DT3H");
        reach(root).put("priority", "pr");

        ScanRequest r = ((ParseResult.Valid) parse(root)).request();

        assertThat(r.formats()).containsExactly(OutputFormat.OPENVEX, OutputFormat.OCSF);
        assertThat(r.dtWriteBack()).isEqualTo(WriteBackMode.VEX);
        assertThat(r.linkTtl()).isEqualTo(Duration.ofDays(2).plusHours(3));
        assertThat(r.priority()).isEqualTo("pr");
    }

    @Test
    void absentOptionalFieldsAreNull() throws IOException {
        ObjectNode root = example();
        reach(root).remove(List.of("formats", "dt_write_back", "link_ttl", "priority"));
        ((ObjectNode) root.at("/unmapped/reachability/dt_project")).remove("uuid");

        ScanRequest r = ((ParseResult.Valid) parse(root)).request();

        assertThat(r.formats()).isNull();
        assertThat(r.dtWriteBack()).isNull();
        assertThat(r.linkTtl()).isNull();
        assertThat(r.priority()).isNull();
        assertThat(r.dtProjectUuid()).isNull();
    }

    @Test
    void reportsEachViolationByPointer() throws IOException {
        ObjectNode root = example();
        ((ObjectNode) root.at("/unmapped/reachability/repo")).put("commit", "main");
        ((ObjectNode) root.at("/unmapped/reachability/dt_project")).remove("version");

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.code()).isEqualTo(ParseResult.ErrorCode.SCHEMA_INVALID);
        assertThat(invalid.violations()).extracting(Violation::pointer)
                .containsExactlyInAnyOrder("/unmapped/reachability/dt_project/version", "/unmapped/reachability/repo/commit");
        assertThat(invalid.violations()).filteredOn(v -> v.pointer().endsWith("/version"))
                .extracting(Violation::message).containsExactly("required");
        assertThat(invalid.schemaVersion()).isEqualTo("1");
    }

    @Test
    void rejectsUnknownPropertyInReachabilityBlock() throws IOException {
        ObjectNode root = example();
        reach(root).put("branch", "main");

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.violations()).extracting(Violation::pointer).containsExactly("/unmapped/reachability/branch");
    }

    @Test
    void enforcesUuidFormat() throws IOException {
        ObjectNode root = example();
        ((ObjectNode) root.at("/unmapped/reachability/dt_project")).put("uuid", "not-a-uuid");

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.violations()).extracting(Violation::pointer).containsExactly("/unmapped/reachability/dt_project/uuid");
    }

    @Test
    void rejectsWrongClass() throws IOException {
        ObjectNode root = example();
        root.put("class_uid", 2002);

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.violations()).extracting(Violation::pointer).containsExactly("/class_uid");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws IOException {
        ObjectNode root = example();
        reach(root).put("schema_version", "2");

        ParseResult.Invalid invalid = (ParseResult.Invalid) parse(root);

        assertThat(invalid.code()).isEqualTo(ParseResult.ErrorCode.UNSUPPORTED_SCHEMA_VERSION);
        assertThat(invalid.schemaVersion()).isEqualTo("2");
    }

    @Test
    void rejectsMalformedJson() {
        ParseResult.Invalid invalid = (ParseResult.Invalid) parser.parse("{\"class_uid\":".getBytes());

        assertThat(invalid.code()).isEqualTo(ParseResult.ErrorCode.INVALID_JSON);
        assertThat(invalid.schemaVersion()).isEqualTo("unknown");
    }

    @Test
    void rejectsNonObjectAndEmptyBodies() {
        assertThat(((ParseResult.Invalid) parser.parse("[]".getBytes())).code()).isEqualTo(ParseResult.ErrorCode.INVALID_JSON);
        assertThat(((ParseResult.Invalid) parser.parse(new byte[0])).code()).isEqualTo(ParseResult.ErrorCode.INVALID_JSON);
    }

    @Test
    void rejectsDuplicateKeys() {
        ParseResult.Invalid invalid = (ParseResult.Invalid) parser.parse("{\"class_uid\":6007,\"class_uid\":6007}".getBytes());

        assertThat(invalid.code()).isEqualTo(ParseResult.ErrorCode.INVALID_JSON);
    }
}
