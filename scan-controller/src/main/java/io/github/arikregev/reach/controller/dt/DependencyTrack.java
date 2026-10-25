package io.github.arikregev.reach.controller.dt;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The DT read path (architecture.md 8.1). 404 on the project lookup is permanent; 5xx, timeouts, connection
 * errors, 429 and auth errors are retryable as DT_UNAVAILABLE (an API key replaced in Vault heals on retry).
 */
@ApplicationScoped
public class DependencyTrack {

    public record Project(UUID uuid, String name, String version) {
    }

    private final DtApi api;
    private final ControllerConfig config;
    private final MeterRegistry registry;

    public DependencyTrack(@RestClient DtApi api, ControllerConfig config, MeterRegistry registry) {
        this.api = api;
        this.config = config;
        this.registry = registry;
    }

    public Project lookupProject(String name, String version) {
        RestResponse<JsonNode> r = call("project_lookup", () -> api.lookupProject(name, version));
        if (r.getStatus() == 404) {
            throw new DtException(ReasonCode.DT_PROJECT_NOT_FOUND,
                    "DT project " + name + "@" + version + " not found", null);
        }
        ensureOk(r, "project lookup");
        JsonNode p = r.getEntity();
        return new Project(uuid(p, "project lookup"), p.path("name").asText(), p.path("version").asText());
    }

    /** All active findings of the project, following {@code X-Total-Count}. */
    public List<DtFinding> findings(UUID projectUuid) {
        List<DtFinding> all = new ArrayList<>();
        int pageSize = config.dt().pageSize();
        int offset = 0;
        while (true) {
            int from = offset;
            RestResponse<JsonNode> r = call("findings", () -> api.findings(projectUuid.toString(), from, pageSize));
            ensureOk(r, "findings");
            JsonNode page = r.getEntity();
            if (page == null || !page.isArray()) {
                throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT findings response is not an array", null);
            }
            for (JsonNode f : page) {
                try {
                    all.add(DtFinding.from(f));
                } catch (IllegalArgumentException e) {
                    throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT returned a malformed finding: " + e.getMessage(), e);
                }
            }
            offset += page.size();
            long seen = offset;
            boolean more = total(r).map(t -> seen < t).orElse(page.size() == pageSize);
            if (!more || page.isEmpty()) {
                return all;
            }
        }
    }

    /** The project's CycloneDX 1.6 JSON SBOM, inventory variant (components and the dependency graph). */
    public JsonNode bom(UUID projectUuid) {
        RestResponse<JsonNode> r = call("bom", () -> api.bom(projectUuid.toString(), "JSON", "inventory", "1.6"));
        ensureOk(r, "SBOM export");
        if (r.getEntity() == null || !r.getEntity().isObject()) {
            throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT SBOM export is not a JSON object", null);
        }
        return r.getEntity();
    }

    /** {@code sha256} of the sorted {@code componentUuid:vulnerabilityUuid} pairs (8.1 step 4), for dedupe. */
    public static String findingsHash(List<DtFinding> findings) {
        List<String> keys = findings.stream()
                .map(f -> f.componentUuid() + ":" + f.vulnerabilityUuid())
                .sorted()
                .toList();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String k : keys) {
                sha.update(k.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) '\n');
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> RestResponse<T> call(String endpoint, Supplier<RestResponse<T>> request) {
        Timer.Sample sample = Timer.start(registry);
        String code = "error";
        try {
            RestResponse<T> r = request.get();
            code = Integer.toString(r.getStatus());
            return r;
        } catch (WebApplicationException e) {
            code = Integer.toString(e.getResponse().getStatus());
            if (e.getResponse().getStatus() == 404) {
                @SuppressWarnings("unchecked")
                RestResponse<T> notFound = (RestResponse<T>) RestResponse.notFound();
                return notFound;
            }
            throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT " + endpoint + " returned " + code, e);
        } catch (ProcessingException e) {
            throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT " + endpoint + " failed: " + e.getMessage(), e);
        } finally {
            sample.stop(registry.timer("reach.dt.request", "endpoint", endpoint, "code", code));
        }
    }

    private static UUID uuid(JsonNode body, String what) {
        try {
            return UUID.fromString(body == null ? "" : body.path("uuid").asText());
        } catch (IllegalArgumentException e) {
            throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT " + what + " returned no valid uuid", e);
        }
    }

    private static Optional<Long> total(RestResponse<?> r) {
        String total = r.getHeaderString("X-Total-Count");
        if (total == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(total.strip()));
        } catch (NumberFormatException e) {
            throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT returned X-Total-Count " + total, e);
        }
    }

    private static void ensureOk(RestResponse<?> r, String what) {
        if (r.getStatus() != 200) {
            throw new DtException(ReasonCode.DT_UNAVAILABLE, "DT " + what + " returned " + r.getStatus(), null);
        }
    }
}
