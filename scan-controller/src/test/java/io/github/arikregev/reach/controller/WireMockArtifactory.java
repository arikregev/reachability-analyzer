package io.github.arikregev.reach.controller;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Mock JFrog Artifactory (D-034, D-044): the Access token endpoints, and the Maven virtual repository proxied to
 * Maven Central. Stubs live in src/test/resources/wiremock/artifactory and are also served by the local compose stack.
 */
public class WireMockArtifactory implements QuarkusTestResourceLifecycleManager {

    static final String ADMIN_TOKEN = "mock-artifactory-admin";

    static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().usingFilesUnderClasspath("wiremock/artifactory"));
        server.start();
        return Map.of("reach.artifactory.url", server.baseUrl(), "reach.artifactory.admin-token", ADMIN_TOKEN);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
