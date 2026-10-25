package io.github.arikregev.reach.controller;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

/**
 * Mock GitHub REST API (D-034): installation lookup, installation tokens and commits. Stubs live in
 * src/test/resources/wiremock/github and are also served by the local compose stack. The App key is generated per
 * run, so no private key is ever committed.
 */
public class WireMockGitHub implements QuarkusTestResourceLifecycleManager {

    static WireMockServer server;
    static KeyPair appKey;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort().usingFilesUnderClasspath("wiremock/github"));
        server.start();
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            appKey = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(appKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return Map.of("reach.github.api-url", server.baseUrl(), "reach.github.private-key", pem);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
