package io.github.arikregev.reach.api.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.arikregev.reach.contracts.Violation;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;

/** RFC 9457 problem body (architecture.md 5.4a a). {@code errors} is present only for 400. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String code, List<Violation> errors) {

    public static final String MEDIA_TYPE = "application/problem+json";
    private static final String TYPE_BASE = "https://reach.example.internal/problems/";

    public static Problem of(int status, String code, String title, String detail) {
        return new Problem(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-'), title, status, detail, code, null);
    }

    public static Problem schemaInvalid(List<Violation> violations) {
        return new Problem(TYPE_BASE + "schema-invalid", "Request does not match the schema", 400,
                violations.size() + (violations.size() == 1 ? " schema violation" : " schema violations"),
                "SCHEMA_INVALID", violations);
    }

    public static Problem invalidParameter(String pointer, String message) {
        return new Problem(TYPE_BASE + "invalid-parameter", "Invalid parameter", 400, message, "INVALID_PARAMETER",
                List.of(new Violation(pointer, message)));
    }

    public Response toResponse() {
        return Response.status(status).type(MEDIA_TYPE).entity(this).build();
    }
}
