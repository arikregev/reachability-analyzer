package io.github.arikregev.reach.controller.dt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/**
 * Adds {@code X-Api-Key}. The key is read from config on every call, so the Vault config source's refresh
 * ({@code quarkus.vault.secret-config-cache-period}, D-032) picks up a key changed in Vault without a restart.
 */
@ApplicationScoped
public class DtApiKeyHeaders implements ClientHeadersFactory {

    static final String KEY_PROPERTY = "reach.dt.api-key-read";

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                 MultivaluedMap<String, String> outgoing) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("X-Api-Key", ConfigProvider.getConfig().getValue(KEY_PROPERTY, String.class));
        return headers;
    }
}
