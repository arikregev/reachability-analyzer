package io.github.arikregev.reach.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The internal contracts between the controller and the scan Job: the input bundle and the summary (D-042). Each
 * instance holds one compiled schema and is thread-safe.
 */
public final class ContractSchema {

    public static final String INPUT = "/contracts/scan-input.v1.schema.json";
    public static final String SUMMARY = "/contracts/scan-summary.v1.schema.json";

    private final JsonSchema schema;

    public ContractSchema(String resource) {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .pathType(PathType.JSON_POINTER)
                .build();
        try (InputStream in = ContractSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Violations as {@code <pointer>: <message>}; empty when the document is valid. */
    public List<String> validate(JsonNode document) {
        return schema.validate(document).stream()
                .map(m -> (m.getInstanceLocation() == null ? "" : m.getInstanceLocation().toString()) + ": " + m.getMessage())
                .sorted()
                .toList();
    }
}
