package io.github.arikregev.reach.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.outbox.OutboxRelay;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockDt.class)
class OutboxAndLeaderTest {

    @Inject
    TestDb db;

    @Inject
    OutboxRelay relay;

    @Inject
    LeaderLock leader;

    @Inject
    DataSource dataSource;

    @Inject
    @Any
    InMemoryConnector connector;

    private InMemorySink<String> sink;

    @BeforeEach
    void reset() {
        db.reset();
        sink = connector.sink("outbox");
        sink.clear();
        leader.tick();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesInOrderWithTopicKeyAndHeaders() {
        db.update("INSERT INTO scan.outbox (topic, key, payload, headers) VALUES "
                + "('appsec.reachability.status.v1', 'k1', '{\"n\":1}', '{\"traceparent\":\"00-abc-def-01\"}'), "
                + "('appsec.reachability.results.v1', 'k2', '{\"n\":2}', '{}')");

        relay.tick();

        List<? extends Message<String>> sent = sink.received();
        assertThat(sent).hasSize(2);
        OutgoingKafkaRecordMetadata<String> first = sent.get(0).getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow();
        assertThat(first.getTopic()).isEqualTo("appsec.reachability.status.v1");
        assertThat(first.getKey()).isEqualTo("k1");
        assertThat(new String(first.getHeaders().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo("00-abc-def-01");
        assertThat(sent.get(0).getPayload()).isEqualTo("{\"n\": 1}");
        assertThat(sent.get(1).getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow().getTopic())
                .isEqualTo("appsec.reachability.results.v1");
        assertThat(db.query("SELECT 1 FROM scan.outbox WHERE published_at IS NULL")).isEmpty();

        relay.tick();
        assertThat(sink.received()).hasSize(2);
    }

    @Test
    void onlyOneHolderOfTheLeaderLock() throws Exception {
        assertThat(leader.isLeader()).isTrue();
        try (Connection other = dataSource.getConnection()) {
            assertThat(tryLock(other)).isFalse();
        }
    }

    @Test
    void standbyTakesOverWhenTheLockIsFree() throws Exception {
        leader.tick();
        assertThat(leader.isLeader()).isTrue();
        // Simulate another replica holding the lock: release ours, take it elsewhere.
        releaseOurs();
        try (Connection other = dataSource.getConnection()) {
            assertThat(tryLock(other)).isTrue();
            leader.tick();
            assertThat(leader.isLeader()).isFalse();
            unlock(other);
        }
        leader.tick();
        assertThat(leader.isLeader()).isTrue();
    }

    private void releaseOurs() throws Exception {
        var release = LeaderLock.class.getDeclaredMethod("release");
        release.setAccessible(true);
        release.invoke(leader);
    }

    private static boolean tryLock(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(7304110001)");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static void unlock(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(7304110001)")) {
            ps.execute();
        }
    }
}
