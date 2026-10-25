package io.github.arikregev.reach.controller.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Mints the read-only installation token a source-fetch Job clones with (architecture.md 3.3, 9). The App key is
 * read from config on every call, so a key replaced in Vault is picked up after the config source's cache period
 * (D-032). 404 means the repo or commit is not visible to the App (COMMIT_NOT_FOUND); 5xx, 429, auth errors and
 * timeouts are GITHUB_UNAVAILABLE.
 */
@ApplicationScoped
public class GitHubApp {

    public static final String PRIVATE_KEY = "reach.github.private-key";

    private final GitHubApi api;
    private final ControllerConfig config;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private volatile String cachedPem;
    private volatile PrivateKey cachedKey;

    public GitHubApp(@RestClient GitHubApi api, ControllerConfig config, ObjectMapper mapper, MeterRegistry registry) {
        this.api = api;
        this.config = config;
        this.mapper = mapper;
        this.registry = registry;
    }

    /** An installation token limited to {@code contents: read}, after checking the commit exists. */
    public String tokenFor(String owner, String repo, String commitSha) {
        String jwt = "Bearer " + AppJwt.sign(appId(), key(), Instant.now());
        Reply installation = call("installation", () -> api.installation(jwt, owner, repo));
        if (installation.status() == 404) {
            throw new GitHubException(ReasonCode.COMMIT_NOT_FOUND,
                    "GitHub App is not installed on " + owner + "/" + repo + " or cannot see it", null);
        }
        ensure(installation, 200, "installation lookup");
        long id = installation.body().path("id").asLong();

        // Not limited to the one repo: submodules in other repos of the installation must stay readable (E-12).
        JsonNode body = mapper.createObjectNode().set("permissions", mapper.createObjectNode().put("contents", "read"));
        Reply minted = call("access_token", () -> api.accessToken(jwt, id, body));
        ensure(minted, 201, "installation token");
        String token = minted.body().path("token").asText();

        Reply commit = call("commit", () -> api.commit("Bearer " + token, owner, repo, commitSha));
        if (commit.status() == 404 || commit.status() == 422) {
            throw new GitHubException(ReasonCode.COMMIT_NOT_FOUND,
                    "commit " + commitSha + " not found in " + owner + "/" + repo, null);
        }
        ensure(commit, 200, "commit lookup");
        return token;
    }

    private String appId() {
        return config.github().appId().orElseThrow(() -> new GitHubException(ReasonCode.GITHUB_UNAVAILABLE,
                "reach.github.app-id is not set", null));
    }

    private PrivateKey key() {
        String pem = ConfigProvider.getConfig().getOptionalValue(PRIVATE_KEY, String.class)
                .orElseThrow(() -> new GitHubException(ReasonCode.GITHUB_UNAVAILABLE, PRIVATE_KEY + " is not set", null));
        if (!pem.equals(cachedPem)) {
            cachedKey = AppJwt.parsePrivateKey(pem);
            cachedPem = pem;
        }
        return cachedKey;
    }

    /** Status and body of a call; 404 and 422 come back as replies, every other error is an exception. */
    private record Reply(int status, JsonNode body) {
    }

    private Reply call(String endpoint, Supplier<RestResponse<JsonNode>> request) {
        Timer.Sample sample = Timer.start(registry);
        String code = "error";
        try {
            RestResponse<JsonNode> r = request.get();
            code = Integer.toString(r.getStatus());
            return new Reply(r.getStatus(), r.getEntity());
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            code = Integer.toString(status);
            if (status == 404 || status == 422) {
                return new Reply(status, null);
            }
            throw new GitHubException(ReasonCode.GITHUB_UNAVAILABLE, "GitHub " + endpoint + " returned " + status, e);
        } catch (ProcessingException e) {
            throw new GitHubException(ReasonCode.GITHUB_UNAVAILABLE, "GitHub " + endpoint + " failed: " + e.getMessage(), e);
        } finally {
            sample.stop(registry.timer("reach.github.request", "endpoint", endpoint, "code", code));
        }
    }

    private static void ensure(Reply r, int expected, String what) {
        if (r.status() != expected || r.body() == null) {
            throw new GitHubException(ReasonCode.GITHUB_UNAVAILABLE, "GitHub " + what + " returned " + r.status(), null);
        }
    }
}
