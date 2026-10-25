package io.github.arikregev.reach.controller.artifactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Mints the read-only token a scan Job resolves dependencies with, and revokes it when the Job ends (D-011, D-044).
 * Each token belongs to a transient user named after the scan, so Artifactory's request log ties every download to
 * one scan (R16). The admin token is read from config on every call, so a token replaced in Vault is picked up after
 * the config source's cache period (D-032).
 */
@ApplicationScoped
public class ArtifactoryTokens {

    private static final Logger LOG = Logger.getLogger(ArtifactoryTokens.class);

    public static final String ADMIN_TOKEN = "reach.artifactory.admin-token";

    /** A minted token: its id for the revoke, and the secret itself. */
    public record Token(String id, String value) {
    }

    private final ArtifactoryApi api;
    private final ControllerConfig config;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;

    public ArtifactoryTokens(@RestClient ArtifactoryApi api, ControllerConfig config, ObjectMapper mapper,
                             MeterRegistry registry) {
        this.api = api;
        this.config = config;
        this.mapper = mapper;
        this.registry = registry;
    }

    /** Whether scan Jobs build through Artifactory; false only on the local stack. */
    public boolean enabled() {
        return config.artifactory().url().isPresent();
    }

    /** Repositories live under {@code /artifactory} of the platform URL; the Access API is at its root. */
    public static String repositoriesUrl(URI platform) {
        return platform.toString().replaceAll("/+$", "") + "/artifactory";
    }

    /** A token for one scan attempt, valid for {@code token-ttl-factor} times the scan Job's deadline. */
    public Token mint(UUID scanId, int attempt, Duration jobDeadline) {
        long ttl = Math.round(jobDeadline.toSeconds() * config.artifactory().tokenTtlFactor());
        JsonNode body = mapper.createObjectNode()
                .put("username", "reach-scan-" + scanId)
                .put("scope", config.artifactory().tokenScope())
                .put("expires_in", ttl)
                .put("refreshable", false)
                .put("description", "reachability scan " + scanId + " attempt " + attempt);
        RestResponse<JsonNode> r = call("create", () -> api.create(admin(), body));
        JsonNode reply = r.getEntity();
        if (reply == null || reply.path("token_id").asText().isEmpty() || reply.path("access_token").asText().isEmpty()) {
            throw new ArtifactoryException("Artifactory token reply had no token_id or access_token", null);
        }
        return new Token(reply.path("token_id").asText(), reply.path("access_token").asText());
    }

    /** Revokes a scan Job's token. Best effort: it expires on its own, so errors are logged, not thrown. */
    public void revoke(String tokenId) {
        try {
            call("revoke", () -> api.revoke(admin(), tokenId));
        } catch (ArtifactoryException e) {
            if (!e.getMessage().endsWith(" 404")) {
                LOG.warnf("Artifactory token revocation failed: %s", e.getMessage());
            }
        }
    }

    private String admin() {
        Optional<String> token = ConfigProvider.getConfig().getOptionalValue(ADMIN_TOKEN, String.class);
        return "Bearer " + token.orElseThrow(() -> new ArtifactoryException(ADMIN_TOKEN + " is not set", null));
    }

    private RestResponse<JsonNode> call(String endpoint, Supplier<RestResponse<JsonNode>> request) {
        Timer.Sample sample = Timer.start(registry);
        String code = "error";
        try {
            RestResponse<JsonNode> r = request.get();
            code = Integer.toString(r.getStatus());
            return r;
        } catch (WebApplicationException e) {
            code = Integer.toString(e.getResponse().getStatus());
            throw new ArtifactoryException("Artifactory token " + endpoint + " returned " + code, e);
        } catch (ProcessingException e) {
            throw new ArtifactoryException("Artifactory token " + endpoint + " failed: " + e.getMessage(), e);
        } finally {
            sample.stop(registry.timer("reach.artifactory.request", "endpoint", endpoint, "code", code));
        }
    }
}
