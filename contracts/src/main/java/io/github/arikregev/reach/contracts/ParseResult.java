package io.github.arikregev.reach.contracts;

import java.util.List;

/** Outcome of {@link ScanRequestParser#parse(byte[])}. */
public sealed interface ParseResult {

    record Valid(ScanRequest request) implements ParseResult {
    }

    /**
     * @param code          INVALID_JSON, SCHEMA_INVALID or UNSUPPORTED_SCHEMA_VERSION (DLQ header {@code reach-error-code}, 5.4a g)
     * @param schemaVersion the record's {@code unmapped.reachability.schema_version}, or {@code unknown}
     */
    record Invalid(ErrorCode code, List<Violation> violations, String schemaVersion) implements ParseResult {
    }

    enum ErrorCode {
        INVALID_JSON,
        SCHEMA_INVALID,
        UNSUPPORTED_SCHEMA_VERSION
    }
}
