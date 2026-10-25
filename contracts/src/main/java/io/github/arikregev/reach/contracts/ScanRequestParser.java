package io.github.arikregev.reach.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parses and validates scan requests against {@code contracts/scan-request.v1.schema.json} (D-028).
 * The same parser serves REST and Kafka so both intakes accept exactly the same requests (D-002). Thread-safe.
 */
public final class ScanRequestParser {

    public static final String SCHEMA_RESOURCE = "/contracts/scan-request.v1.schema.json";
    public static final String SUPPORTED_SCHEMA_VERSION = "1";
    private static final String SCHEMA_VERSION_POINTER = "/unmapped/reachability/schema_version";
    private static final String LINK_TTL_POINTER = "/unmapped/reachability/link_ttl";

    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public ScanRequestParser() {
        this.mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .pathType(PathType.JSON_POINTER)
                .build();
        try (InputStream in = ScanRequestParser.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + SCHEMA_RESOURCE);
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ParseResult parse(byte[] body) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return new ParseResult.Invalid(ParseResult.ErrorCode.INVALID_JSON,
                    List.of(new Violation("", "not valid JSON: " + e.getOriginalMessage())), "unknown");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (root == null || !root.isObject()) {
            return new ParseResult.Invalid(ParseResult.ErrorCode.INVALID_JSON,
                    List.of(new Violation("", "must be a JSON object")), "unknown");
        }

        JsonNode versionNode = root.at(SCHEMA_VERSION_POINTER);
        String schemaVersion = versionNode.isTextual() ? versionNode.asText() : "unknown";
        if (versionNode.isTextual() && !SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
            return new ParseResult.Invalid(ParseResult.ErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                    List.of(new Violation(SCHEMA_VERSION_POINTER, "unsupported schema_version " + schemaVersion)),
                    schemaVersion);
        }

        Set<ValidationMessage> messages = schema.validate(root);
        if (!messages.isEmpty()) {
            List<Violation> violations = messages.stream()
                    .map(ScanRequestParser::toViolation)
                    .sorted(Comparator.comparing(Violation::pointer).thenComparing(Violation::message))
                    .toList();
            return new ParseResult.Invalid(ParseResult.ErrorCode.SCHEMA_INVALID, violations, schemaVersion);
        }
        List<Violation> extra = new ArrayList<>();
        // Postgres rejects U+0000 in jsonb, and the raw request is stored as jsonb.
        findNul(root, "", extra);
        JsonNode ttl = root.at(LINK_TTL_POINTER);
        if (ttl.isTextual()) {
            try {
                Duration.parse(ttl.asText());
            } catch (DateTimeParseException | ArithmeticException e) {
                extra.add(new Violation(LINK_TTL_POINTER, "duration is out of range"));
            }
        }
        if (!extra.isEmpty()) {
            return new ParseResult.Invalid(ParseResult.ErrorCode.SCHEMA_INVALID, extra, schemaVersion);
        }
        return new ParseResult.Valid(toRequest(root));
    }

    private static void findNul(JsonNode node, String pointer, List<Violation> out) {
        if (node.isTextual() && node.asText().indexOf('\0') >= 0) {
            out.add(new Violation(pointer, "must not contain U+0000"));
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String child = pointer + "/" + escape(e.getKey());
                if (e.getKey().indexOf('\0') >= 0) {
                    out.add(new Violation(child, "property name must not contain U+0000"));
                }
                findNul(e.getValue(), child, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                findNul(node.get(i), pointer + "/" + i, out);
            }
        }
    }

    private static Violation toViolation(ValidationMessage m) {
        String pointer = m.getInstanceLocation() == null ? "" : m.getInstanceLocation().toString();
        if ("$".equals(pointer)) {
            pointer = "";
        }
        // For "required" and "additionalProperties" the location is the parent object; point at the property itself.
        if (("required".equals(m.getType()) || "additionalProperties".equals(m.getType())) && m.getProperty() != null) {
            return new Violation(pointer + "/" + escape(m.getProperty()),
                    "required".equals(m.getType()) ? "required" : "unknown property");
        }
        return new Violation(pointer, m.getError() != null ? m.getError() : m.getMessage());
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static ScanRequest toRequest(JsonNode root) {
        JsonNode r = root.at("/unmapped/reachability");
        List<OutputFormat> formats = null;
        if (r.has("formats")) {
            formats = new ArrayList<>();
            for (JsonNode f : r.get("formats")) {
                formats.add(OutputFormat.fromWire(f.asText()));
            }
            formats = List.copyOf(formats);
        }
        return new ScanRequest(
                text(root.at("/metadata/uid")),
                text(root.at("/scan/uid")),
                root.get("activity_id").asInt(),
                root.get("time").asLong(),
                text(root.at("/metadata/version")),
                text(root.at("/metadata/product/name")),
                text(r.at("/repo/url")),
                text(r.at("/repo/commit")),
                text(r.at("/dt_project/name")),
                text(r.at("/dt_project/version")),
                r.at("/dt_project/uuid").isTextual() ? UUID.fromString(r.at("/dt_project/uuid").asText()) : null,
                text(r.get("priority")),
                formats,
                r.hasNonNull("dt_write_back") ? WriteBackMode.valueOf(r.get("dt_write_back").asText()) : null,
                r.hasNonNull("link_ttl") ? Duration.parse(r.get("link_ttl").asText()) : null,
                root);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
