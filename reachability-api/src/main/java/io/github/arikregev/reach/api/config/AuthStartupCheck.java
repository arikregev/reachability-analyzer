package io.github.arikregev.reach.api.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to start a production instance whose OIDC tenant accepts tokens for any audience (D-039). Without an
 * audience check, a token the shared IdP minted for another service would pass here as long as it carries the groups.
 */
@Singleton
public class AuthStartupCheck {

    void check(@Observes StartupEvent event, LaunchMode mode,
               @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "true") boolean tenantEnabled,
               @ConfigProperty(name = "quarkus.oidc.token.audience") Optional<List<String>> audience) {
        if (mode == LaunchMode.NORMAL && tenantEnabled
                && audience.map(a -> a.stream().allMatch(String::isBlank)).orElse(true)) {
            throw new IllegalStateException(
                    "quarkus.oidc.token.audience must be set (REACH_OIDC_AUDIENCE); see docs/decisions.md D-039");
        }
    }
}
