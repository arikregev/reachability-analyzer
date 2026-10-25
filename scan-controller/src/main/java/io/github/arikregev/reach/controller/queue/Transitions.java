package io.github.arikregev.reach.controller.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.common.StatusEvents;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.dt.DtFinding;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.github.arikregev.reach.controller.store.OutboxStore;
import io.github.arikregev.reach.controller.store.ScanStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Each state change of architecture.md 4.2 that the controller makes, in one transaction with its status.v1
 * event in the outbox (D-006).
 */
@ApplicationScoped
public class Transitions {

    private final ScanStore scans;
    private final OutboxStore outbox;
    private final ControllerConfig config;
    private final StatusEvents events;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final String statusTopic;

    public Transitions(ScanStore scans, OutboxStore outbox, ControllerConfig config, ObjectMapper mapper,
                       MeterRegistry registry,
                       @ConfigProperty(name = "reach.kafka.topics.status", defaultValue = "appsec.reachability.status.v1")
                       String statusTopic) {
        this.scans = scans;
        this.outbox = outbox;
        this.config = config;
        this.mapper = mapper;
        this.events = new StatusEvents(mapper);
        this.registry = registry;
        this.statusTopic = statusTopic;
    }

    /** QUEUED to FETCHING for up to {@code batch} scans (6.2). */
    @Transactional
    public List<ClaimedScan> claim(int batch) {
        List<ClaimedScan> claimed = scans.claim(batch, config.queue().orgMaxRunning().orElse(null),
                config.queue().repoMaxRunning());
        Instant now = scans.now();
        for (ClaimedScan s : claimed) {
            emit(s, events.progress(s.eventScan(), now, ScanStatus.FETCHING, s.attempt()));
            registry.timer("reach.queue.wait", "class", s.priorityClass())
                    .record(Duration.between(s.enqueuedAt(), now));
        }
        return claimed;
    }

    /** DT data loaded; the scan stays FETCHING until its source-fetch Job has uploaded the tarball. */
    @Transactional
    public boolean findingsLoaded(ClaimedScan scan, UUID dtProjectUuid, String hash, List<DtFinding> findings) {
        return scans.saveFindings(scan, dtProjectUuid, hash, findings);
    }

    /** FETCHING to SCANNING, SCANNING to PUBLISHING, or FETCHING to PUBLISHING when there is nothing to analyse. */
    @Transactional
    public boolean advance(ClaimedScan scan, ScanStatus from, ScanStatus to) {
        if (!scans.move(scan, from, to)) {
            return false;
        }
        emit(scan, events.progress(scan.eventScan(), scans.now(), to, scan.attempt()));
        return true;
    }

    /** A failure during FETCHING, see {@link #failed}. */
    @Transactional
    public void fetchFailed(ClaimedScan scan, ReasonCode reason, String detail) {
        failed(scan, ScanStatus.FETCHING, reason, detail);
    }

    /**
     * A failure during FETCHING or SCANNING: a retryable reason requeues with exponential backoff until
     * {@code reach.retry.max-attempts} is used up, then fails with the same code (5.4a f).
     */
    @Transactional
    public void failed(ClaimedScan scan, ScanStatus from, ReasonCode reason, String detail) {
        Instant now = scans.now();
        if (reason.retryable() && scan.attempt() < config.retry().maxAttempts()) {
            Duration wait = config.retry().backoff().multipliedBy(1L << Math.min(scan.attempt() - 1, 20));
            Instant notBefore = now.plus(wait);
            Optional<Integer> attempt = scans.requeue(scan, from, reason, detail, notBefore);
            attempt.ifPresent(a -> emit(scan, events.retry(scan.eventScan(), now, a, reason, detail, notBefore)));
            return;
        }
        if (scans.fail(scan, from, reason, detail)) {
            emit(scan, events.failed(scan.eventScan(), now, scan.attempt(), reason, detail));
            registry.counter("reach.scans", "status", ScanStatus.FAILED.name(), "reason", reason.name()).increment();
        }
    }

    private void emit(ClaimedScan scan, ObjectNode event) {
        try {
            outbox.insert(statusTopic, scan.id().toString(), mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
