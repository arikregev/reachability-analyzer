package io.github.arikregev.reach.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

/** Builds requests from the published example (docs/contracts/scan-request.v1.example.json). */
public final class TestRequests {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private TestRequests() {
    }

    /** The example with a fresh {@code metadata.uid} and no {@code priority} (tests that need one set it). */
    public static ObjectNode example() {
        try (InputStream in = TestRequests.class.getResourceAsStream("/contracts/scan-request.v1.example.json")) {
            ObjectNode root = (ObjectNode) MAPPER.readTree(in);
            ((ObjectNode) root.get("metadata")).put("uid", "test-" + UUID.randomUUID());
            reach(root).remove("priority");
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode reach(ObjectNode root) {
        return (ObjectNode) root.at("/unmapped/reachability");
    }

    public static String uid(ObjectNode root) {
        return root.at("/metadata/uid").asText();
    }

    public static byte[] bytes(ObjectNode root) {
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
