package io.github.arikregev.reach.api.kafka;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.CompletionStage;
import org.apache.kafka.common.header.Headers;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Consumer of {@code appsec.reachability.requests.v1} (D-005). The record is acknowledged, and its offset becomes
 * committable, only after the Postgres insert (or the DLQ write) succeeded (D-006).
 */
@ApplicationScoped
public class KafkaIntake {

    private final KafkaRecordHandler handler;

    public KafkaIntake(KafkaRecordHandler handler) {
        this.handler = handler;
    }

    @Incoming("scan-requests")
    @Blocking
    @SuppressWarnings("unchecked")
    public CompletionStage<Void> consume(Message<byte[]> message) {
        byte[] key = null;
        Headers headers = null;
        var metadata = message.getMetadata(IncomingKafkaRecordMetadata.class);
        if (metadata.isPresent()) {
            IncomingKafkaRecordMetadata<Object, byte[]> m = metadata.get();
            key = m.getKey() instanceof byte[] k ? k : null;
            headers = m.getHeaders();
        }
        handler.handle(key, message.getPayload(), headers);
        return message.ack();
    }
}
