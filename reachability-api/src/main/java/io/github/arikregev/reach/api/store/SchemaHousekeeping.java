package io.github.arikregev.reach.api.store;

import io.github.arikregev.reach.api.config.ReachConfig;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Seeds priority classes, keeps the monthly partitions ahead of time and applies Postgres retention. Runs after
 * Liquibase at start-up.
 */
@ApplicationScoped
public class SchemaHousekeeping {

    private static final Logger LOG = Logger.getLogger(SchemaHousekeeping.class);

    private final PolicyStore store;
    private final ReachConfig config;

    public SchemaHousekeeping(PolicyStore store, ReachConfig config) {
        this.store = store;
        this.config = config;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        int seeded = store.seedPriorityClasses(config.queue().classes());
        if (seeded > 0) {
            LOG.infof("Seeded %d priority class(es) from configuration", seeded);
        }
        ensurePartitions();
    }

    @Scheduled(cron = "{reach.partitions.cron:0 15 3 * * ?}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void ensurePartitions() {
        int created = store.ensurePartitions(config.partitions().monthsAhead());
        if (created > 0) {
            LOG.infof("Created %d monthly partition(s) of scan.scan and scan.finding", created);
        }
    }

    /** Drops the partitions older than {@code reach.retention.postgres}, and the queue and key rows into them (D-038). */
    @Scheduled(cron = "{reach.retention.cron:0 45 3 * * ?}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void applyRetention() {
        int dropped = store.applyRetention(config.retention().postgres());
        if (dropped > 0) {
            LOG.infof("Retention dropped %d partition(s) older than %s", dropped, config.retention().postgres());
        }
    }
}
