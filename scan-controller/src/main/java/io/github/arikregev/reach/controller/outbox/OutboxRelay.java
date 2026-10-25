package io.github.arikregev.reach.controller.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.store.OutboxStore;
import io.github.arikregev.reach.controller.store.StoreUnavailableException;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Publishes outbox rows to Kafka in insertion order and marks them published after the broker acknowledged them.
 * At-least-once: a crash between send and mark republishes, and consumers dedupe (5.4a e). Leader only.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() { };

    private final LeaderLock leader;
    private final OutboxStore outbox;
    private final MutinyEmitter<String> emitter;
    private final ControllerConfig config;
    private final ObjectMapper mapper;

    public OutboxRelay(LeaderLock leader, OutboxStore outbox, @Channel("outbox") MutinyEmitter<String> emitter,
                       ControllerConfig config, ObjectMapper mapper) {
        this.leader = leader;
        this.outbox = outbox;
        this.emitter = emitter;
        this.config = config;
        this.mapper = mapper;
    }

    @Scheduled(every = "{reach.outbox.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void tick() {
        if (!leader.isLeader()) {
            return;
        }
        try {
            // A full batch means there may be more; leadership is re-checked between batches.
            while (leader.isLeader() && publishBatch() == config.outbox().batchSize()) {
            }
        } catch (StoreUnavailableException e) {
            LOG.warnf("Outbox round skipped: %s", e.getMessage());
        } catch (RuntimeException e) {
            // Kafka unavailable: rows stay in the outbox and are published on recovery (section 11).
            LOG.warnf("Outbox publish failed, will retry: %s", e.getMessage());
        }
    }

    /** Publishes one batch; returns how many rows were published. */
    int publishBatch() {
        List<OutboxStore.Row> rows = outbox.unpublished(config.outbox().batchSize());
        if (rows.isEmpty()) {
            return 0;
        }
        List<Uni<Void>> sends = new ArrayList<>();
        for (OutboxStore.Row row : rows) {
            RecordHeaders headers = new RecordHeaders();
            headers(row).forEach((k, v) -> headers.add(k, v.getBytes(StandardCharsets.UTF_8)));
            OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String>builder()
                    .withTopic(row.topic())
                    .withKey(row.key())
                    .withHeaders(headers)
                    .build();
            sends.add(emitter.sendMessage(Message.of(row.payload()).addMetadata(metadata)));
        }
        // Bounded, so a slow broker cannot hold the tick (and a lost leadership) for delivery.timeout.ms.
        Uni.join().all(sends).andFailFast().await().atMost(config.outbox().sendTimeout());
        if (!leader.isLeader()) {
            // The new leader republishes these rows; marking them here would only add duplicates.
            return 0;
        }
        outbox.markPublished(rows.stream().map(OutboxStore.Row::id).toList());
        return rows.size();
    }

    private Map<String, String> headers(OutboxStore.Row row) {
        if (row.headers() == null || row.headers().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(row.headers(), HEADERS);
        } catch (JsonProcessingException e) {
            LOG.warnf("Outbox row %d has unreadable headers; sending without them", row.id());
            return Map.of();
        }
    }
}
