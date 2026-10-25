package io.github.arikregev.reach.controller.outbox;

import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.store.OutboxStore;
import io.github.arikregev.reach.controller.store.StoreUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/** Purges published outbox rows and refreshes the queue and outbox gauges (architecture.md 11). */
@ApplicationScoped
public class Housekeeping {

    private static final Logger LOG = Logger.getLogger(Housekeeping.class);

    private final LeaderLock leader;
    private final OutboxStore outbox;
    private final ControllerConfig config;
    private final MeterRegistry registry;
    private final AtomicReference<Double> lag = new AtomicReference<>(0.0);
    private final Map<String, Integer> depth = new ConcurrentHashMap<>();

    public Housekeeping(LeaderLock leader, OutboxStore outbox, ControllerConfig config, MeterRegistry registry) {
        this.leader = leader;
        this.outbox = outbox;
        this.config = config;
        this.registry = registry;
        registry.gauge("reach.outbox.lag.seconds", lag, AtomicReference::get);
    }

    @Scheduled(every = "{reach.metrics.refresh-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refreshGauges() {
        if (!leader.isLeader()) {
            return;
        }
        try {
            lag.set(outbox.lagSeconds());
            outbox.queueDepth().forEach((cls, n) -> {
                if (depth.put(cls, n) == null) {
                    Gauge.builder("reach.queue.depth", depth, d -> d.getOrDefault(cls, 0)).tag("class", cls)
                            .register(registry);
                }
            });
        } catch (StoreUnavailableException e) {
            LOG.debugf("Gauge refresh skipped: %s", e.getMessage());
        }
    }

    @Scheduled(cron = "{reach.outbox.purge-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void purge() {
        if (!leader.isLeader()) {
            return;
        }
        try {
            int deleted = outbox.purgePublished(config.outbox().retention());
            if (deleted > 0) {
                LOG.infof("Purged %d published outbox row(s)", deleted);
            }
        } catch (StoreUnavailableException e) {
            LOG.warnf("Outbox purge skipped: %s", e.getMessage());
        }
    }
}
