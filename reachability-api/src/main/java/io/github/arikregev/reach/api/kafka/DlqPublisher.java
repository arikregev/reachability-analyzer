package io.github.arikregev.reach.api.kafka;

import io.github.arikregev.reach.contracts.Violation;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

/** Sends a rejected request to {@code requests.dlq.v1} with the original bytes and headers (5.4a g). */
@ApplicationScoped
public class DlqPublisher {

    static final int MAX_DETAIL_BYTES = 1024;

    private final MutinyEmitter<byte[]> emitter;

    public DlqPublisher(@Channel("scan-requests-dlq") MutinyEmitter<byte[]> emitter) {
        this.emitter = emitter;
    }

    /** Blocks until the broker acknowledges, so the request offset is committed only after the DLQ write. */
    public void publish(byte[] key, byte[] value, Headers original, String code, List<Violation> violations,
                        String schemaVersion) {
        RecordHeaders headers = new RecordHeaders(original == null ? new RecordHeaders().toArray() : original.toArray());
        headers.add("reach-error-code", utf8(code));
        headers.add("reach-error-detail", utf8(truncate(violations.stream().map(Violation::toString)
                .collect(Collectors.joining("; ")))));
        headers.add("reach-schema-version", utf8(schemaVersion));
        headers.add("reach-received-at", utf8(Instant.now().toString()));
        OutgoingKafkaRecordMetadata<byte[]> metadata = OutgoingKafkaRecordMetadata.<byte[]>builder()
                .withKey(key)
                .withHeaders(headers)
                .build();
        emitter.sendMessageAndAwait(Message.of(value == null ? new byte[0] : value).addMetadata(metadata));
    }

    static String truncate(String detail) {
        byte[] bytes = detail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_DETAIL_BYTES) {
            return detail;
        }
        // Cut on a character boundary.
        String cut = new String(bytes, 0, MAX_DETAIL_BYTES, StandardCharsets.UTF_8);
        while (cut.getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES || cut.endsWith("�")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
