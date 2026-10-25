package io.github.arikregev.reach.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatusEventsTest {

    private final StatusEvents events = new StatusEvents(new ObjectMapper());
    private final StatusEvents.Scan scan = new StatusEvents.Scan(UUID.randomUUID(), "ci-1", "acme/payments", "main", null);
    private final Instant now = Instant.parse("2026-10-25T23:00:00Z");

    @Test
    void failedMatchesTheDocumentedExample() {
        ObjectNode e = events.failed(scan, now, 1, ReasonCode.DT_PROJECT_NOT_FOUND, "DT project acme/payments@main not found");

        assertThat(e.path("activity_id").asInt()).isEqualTo(6);
        assertThat(e.path("type_uid").asInt()).isEqualTo(600706);
        assertThat(e.path("status_id").asInt()).isEqualTo(2);
        assertThat(e.path("status").asText()).isEqualTo("Failure");
        assertThat(e.at("/metadata/uid").asText()).isEqualTo(scan.scanId().toString());
        assertThat(e.at("/metadata/correlation_uid").asText()).isEqualTo("ci-1");
        assertThat(e.at("/unmapped/reachability/scan_status").asText()).isEqualTo("FAILED");
        assertThat(e.at("/unmapped/reachability/reason").asText()).isEqualTo("DT_PROJECT_NOT_FOUND");
        assertThat(e.at("/unmapped/reachability/retryable").asBoolean()).isFalse();
        assertThat(e.at("/unmapped/reachability/dt_project/uuid").isNull()).isTrue();
    }

    @Test
    void queuedIsStartedWithWarnings() {
        ObjectNode e = events.queued(scan, now, List.of());

        assertThat(e.path("activity_id").asInt()).isEqualTo(1);
        assertThat(e.path("type_uid").asInt()).isEqualTo(600701);
        assertThat(e.path("status").asText()).isEqualTo("QUEUED");
        assertThat(e.at("/unmapped/reachability/attempt").asInt()).isEqualTo(1);
        assertThat(e.at("/unmapped/reachability/warnings").isArray()).isTrue();
    }

    @Test
    void retryCarriesReasonAndNotBefore() {
        ObjectNode e = events.retry(scan, now, 2, ReasonCode.DT_UNAVAILABLE, "DT returned 503", now.plusSeconds(60));

        assertThat(e.path("activity_id").asInt()).isEqualTo(99);
        assertThat(e.at("/unmapped/reachability/retryable").asBoolean()).isTrue();
        assertThat(e.at("/unmapped/reachability/not_before").asText()).isEqualTo("2026-10-25T23:01:00Z");
        assertThat(e.at("/unmapped/reachability/attempt").asInt()).isEqualTo(2);
    }

    @Test
    void omitsCorrelationWithoutRequestUid() {
        ObjectNode e = events.progress(new StatusEvents.Scan(scan.scanId(), null, "p", "v", null), now, ScanStatus.FETCHING, 1);

        assertThat(e.path("metadata").has("correlation_uid")).isFalse();
        assertThat(e.path("status").asText()).isEqualTo("FETCHING");
    }
}
