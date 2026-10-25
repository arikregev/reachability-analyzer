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
import java.util.Set;
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
    private final AtomicReference<Double> lag = new AtomicReference<>(Double.NaN);
    private final Map<String, Double> depth = new ConcurrentHashMap<>();
    private final Map<String, Gauge> depthGauges = new ConcurrentHashMap<>();

    public Housekeeping(LeaderLock leader, OutboxStore outbox, ControllerConfig config, MeterRegistry registry) {
        this.leader = leader;
        this.outbox = outbox;
        this.config = config;
        this.registry = registry;
        registry.gauge("reach.outbox.lag.seconds", lag, AtomicReference::get);
    }

    /** Only the leader reports values; a standby reports NaN, so sums and maxima across pods stay right. */
    @Scheduled(every = "{reach.metrics.refresh-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refreshGauges() {
        if (!leader.isLeader()) {
            lag.set(Double.NaN);
            depth.replaceAll((cls, n) -> Double.NaN);
            return;
        }
        try {
            lag.set(outbox.lagSeconds());
            Map<String, Integer> current = outbox.queueDepth();
            current.forEach((cls, n) -> {
                depth.put(cls, (double) n);
                depthGauges.computeIfAbsent(cls, c -> Gauge.builder("reach.queue.depth", depth,
                        d -> d.getOrDefault(c, Double.NaN)).tag("class", c).register(registry));
            });
            // Classes deleted from scan.priority_class stop being reported.
            for (String cls : Set.copyOf(depthGauges.keySet())) {
                if (!current.containsKey(cls)) {
                    registry.remove(depthGauges.remove(cls));
                    depth.remove(cls);
                }
            }
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
