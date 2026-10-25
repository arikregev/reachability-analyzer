package io.github.arikregev.reach.controller;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Mock Dependency-Track (D-034). Stubs live in src/test/resources/wiremock/dt and are also served by the local
 * compose stack, so tests and manual runs see the same DT.
 */
public class WireMockDt implements QuarkusTestResourceLifecycleManager {

    static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().usingFilesUnderClasspath("wiremock/dt"));
        server.start();
        return Map.of("reach.dt.url", server.baseUrl());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
