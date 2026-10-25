package io.github.arikregev.reach.api;

import static io.github.arikregev.reach.api.TestRequests.bytes;
import static io.github.arikregev.reach.api.TestRequests.example;
import static io.github.arikregev.reach.api.TestRequests.reach;
import static io.github.arikregev.reach.api.TestRequests.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class KafkaIntakeTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    Db db;

    private InMemorySource<byte[]> requests;
    private InMemorySink<byte[]> dlq;

    @BeforeEach
    void channels() {
        requests = connector.source("scan-requests");
        dlq = connector.sink("scan-requests-dlq");
        dlq.clear();
    }

    private List<Map<String, Object>> scanFor(String requestUid) {
        return db.query("SELECT source, priority_class, status FROM scan.scan WHERE request_uid = ?", requestUid);
    }

    @Test
    void validRecordBecomesAQueuedScan() {
        ObjectNode request = example();

        requests.send(bytes(request));

        await().atMost(Duration.ofSeconds(10)).until(() -> !scanFor(uid(request)).isEmpty());
        assertThat(scanFor(uid(request))).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("source", "KAFKA").containsEntry("status", "QUEUED"));
        assertThat(dlq.received()).isEmpty();
    }

    @Test
    void redeliveryDoesNotCreateASecondScan() {
        ObjectNode request = example();

        requests.send(bytes(request));
        requests.send(bytes(request));

        await().atMost(Duration.ofSeconds(10)).until(() -> !scanFor(uid(request)).isEmpty());
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(scanFor(uid(request))).hasSize(1));
    }

    @Test
    void kafkaPriorityNeedsTheAllowList() {
        ObjectNode pr = example();
        reach(pr).put("priority", "pr");
        ObjectNode critical = example();
        reach(critical).put("priority", "critical");

        requests.send(bytes(pr));
        requests.send(bytes(critical));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !scanFor(uid(pr)).isEmpty() && !scanFor(uid(critical)).isEmpty());
        assertThat(scanFor(uid(pr)).getFirst()).containsEntry("priority_class", "default");
        assertThat(scanFor(uid(critical)).getFirst()).containsEntry("priority_class", "critical");
    }

    @Test
    void schemaInvalidRecordGoesToTheDlqWithHeaders() {
        ObjectNode request = example();
        ((ObjectNode) request.at("/unmapped/reachability/repo")).put("commit", "main");
        byte[] original = bytes(request);

        requests.send(original);

        await().atMost(Duration.ofSeconds(10)).until(() -> dlq.received().size() == 1);
        Message<byte[]> dead = dlq.received().getFirst();
        assertThat(dead.getPayload()).isEqualTo(original);
        Map<String, String> headers = headers(dead);
        assertThat(headers).containsEntry("reach-error-code", "SCHEMA_INVALID")
                .containsEntry("reach-schema-version", "1")
                .containsKey("reach-received-at");
        assertThat(headers.get("reach-error-detail")).startsWith("/unmapped/reachability/repo/commit: ");
        assertThat(scanFor(uid(request))).isEmpty();
    }

    @Test
    void oversizedRecordGoesToTheDlqUnparsed() {
        ObjectNode request = example();
        request.put("padding", "x".repeat(300_000));

        requests.send(bytes(request));

        await().atMost(Duration.ofSeconds(10)).until(() -> dlq.received().size() == 1);
        assertThat(headers(dlq.received().getFirst())).containsEntry("reach-error-code", "PAYLOAD_TOO_LARGE");
        assertThat(scanFor(uid(request))).isEmpty();
    }

    @Test
    void ruleViolationGoesToTheDlq() {
        ObjectNode request = example();
        ((ObjectNode) request.at("/unmapped/reachability/repo")).put("url", "https://gitlab.com/acme/payments");

        requests.send(bytes(request));

        await().atMost(Duration.ofSeconds(10)).until(() -> dlq.received().size() == 1);
        assertThat(headers(dlq.received().getFirst())).containsEntry("reach-error-code", "SCHEMA_INVALID");
    }

    @Test
    void unreadableRecordsGoToTheDlq() {
        requests.send("not json".getBytes(StandardCharsets.UTF_8));
        ObjectNode v2 = example();
        reach(v2).put("schema_version", "2");
        requests.send(bytes(v2));

        await().atMost(Duration.ofSeconds(10)).until(() -> dlq.received().size() == 2);
        assertThat(headers(dlq.received().get(0)))
                .containsEntry("reach-error-code", "INVALID_JSON").containsEntry("reach-schema-version", "unknown");
        assertThat(headers(dlq.received().get(1)))
                .containsEntry("reach-error-code", "UNSUPPORTED_SCHEMA_VERSION").containsEntry("reach-schema-version", "2");
    }

    @Test
    void nulAndOverflowRecordsGoToTheDlqAndIntakeContinues() {
        ObjectNode nul = example();
        ((ObjectNode) nul.get("metadata")).put("uid", "kafka-nul-\u0000");
        ObjectNode overflow = example();
        ((ObjectNode) overflow.get("metadata")).put("uid", "kafka-overflow");
        reach(overflow).put("link_ttl", "P999999999999999D");
        ObjectNode valid = example();
        ((ObjectNode) valid.get("metadata")).put("uid", "kafka-after-poison");

        requests.send(bytes(nul));
        requests.send(bytes(overflow));
        requests.send(bytes(valid));

        await().atMost(Duration.ofSeconds(10)).until(() -> !scanFor("kafka-after-poison").isEmpty());
        assertThat(dlq.received()).hasSize(2).allSatisfy(m ->
                assertThat(headers(m)).containsEntry("reach-error-code", "SCHEMA_INVALID"));
    }

    @Test
    void failureAtCommitIsRetriedNotSentToTheDlq() {
        // A deferred constraint trigger fails the first commit with a connection-loss SQLState, as a failover
        // during commit would. The error surfaces from the transaction interceptor, not from our JDBC calls.
        db.update("CREATE SEQUENCE scan.test_commit_once");
        db.update("""
                CREATE OR REPLACE FUNCTION scan.test_commit_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.request_uid = 'kafka-commit-fail' AND nextval('scan.test_commit_once') = 1 THEN
                    RAISE EXCEPTION 'connection lost at commit' USING ERRCODE = '08006';
                  END IF;
                  RETURN NULL;
                END $$""");
        db.update("""
                CREATE CONSTRAINT TRIGGER test_commit_fail AFTER INSERT ON scan.scan DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION scan.test_commit_fail()""");
        try {
            ObjectNode request = example();
            ((ObjectNode) request.get("metadata")).put("uid", "kafka-commit-fail");

            requests.send(bytes(request));

            await().atMost(Duration.ofSeconds(15)).until(() -> !scanFor("kafka-commit-fail").isEmpty());
            assertThat(db.query("SELECT last_value FROM scan.test_commit_once").getFirst())
                    .containsEntry("last_value", 2L);
            assertThat(dlq.received()).isEmpty();
        } finally {
            db.update("DROP TRIGGER test_commit_fail ON scan.scan");
            db.update("DROP FUNCTION scan.test_commit_fail()");
            db.update("DROP SEQUENCE scan.test_commit_once");
        }
    }

    @Test
    void recordThatFailsInPostgresGoesToTheDlqAsUnprocessable() {
        // A data error that is not an outage (here forced by a trigger) must not stop the channel.
        db.update("""
                CREATE OR REPLACE FUNCTION scan.test_poison() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.request_uid = 'kafka-poison' THEN RAISE EXCEPTION 'poison' USING ERRCODE = '22023'; END IF;
                  RETURN NEW;
                END $$""");
        db.update("CREATE TRIGGER test_poison BEFORE INSERT ON scan.scan FOR EACH ROW EXECUTE FUNCTION scan.test_poison()");
        try {
            ObjectNode poison = example();
            ((ObjectNode) poison.get("metadata")).put("uid", "kafka-poison");
            ObjectNode valid = example();
            ((ObjectNode) valid.get("metadata")).put("uid", "kafka-after-unprocessable");

            requests.send(bytes(poison));
            requests.send(bytes(valid));

            await().atMost(Duration.ofSeconds(10)).until(() -> !scanFor("kafka-after-unprocessable").isEmpty());
            assertThat(dlq.received()).singleElement().satisfies(m ->
                    assertThat(headers(m)).containsEntry("reach-error-code", "UNPROCESSABLE"));
        } finally {
            db.update("DROP TRIGGER test_poison ON scan.scan");
            db.update("DROP FUNCTION scan.test_poison()");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Message<byte[]> message) {
        OutgoingKafkaRecordMetadata<byte[]> metadata = message.getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow();
        Map<String, String> headers = new HashMap<>();
        for (Header h : metadata.getHeaders()) {
            headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }
}
