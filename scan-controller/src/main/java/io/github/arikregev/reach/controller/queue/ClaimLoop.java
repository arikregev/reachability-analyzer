package io.github.arikregev.reach.controller.queue;

import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.leader.LeadershipAcquired;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.github.arikregev.reach.controller.store.ScanStore;
import io.github.arikregev.reach.controller.store.StoreUnavailableException;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.jboss.logging.Logger;

/**
 * The leader's claim loop (architecture.md 6.2): while there is free capacity, claim the best queued scans and
 * load their DT data in parallel, at most {@code reach.dt.max-concurrent} at a time.
 */
@ApplicationScoped
public class ClaimLoop {

    private static final Logger LOG = Logger.getLogger(ClaimLoop.class);

    private final LeaderLock leader;
    private final ScanStore scans;
    private final Transitions transitions;
    private final FetchStage fetch;
    private final ControllerConfig config;
    private final Semaphore inflight;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ClaimLoop(LeaderLock leader, ScanStore scans, Transitions transitions, FetchStage fetch,
                     ControllerConfig config) {
        this.leader = leader;
        this.scans = scans;
        this.transitions = transitions;
        this.fetch = fetch;
        this.config = config;
        this.inflight = new Semaphore(config.dt().maxConcurrent());
    }

    @Scheduled(every = "{reach.queue.claim-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void tick() {
        if (!leader.isLeader()) {
            return;
        }
        try {
            int globalCap = scans.globalLimit().orElse(config.queue().globalMaxRunning());
            int capacity = globalCap - scans.runningTotal();
            while (capacity > 0 && inflight.availablePermits() > 0) {
                int batch = Math.min(config.queue().claimBatch(), Math.min(capacity, inflight.availablePermits()));
                List<ClaimedScan> claimed = transitions.claim(batch);
                if (claimed.isEmpty()) {
                    return;
                }
                claimed.forEach(this::submit);
                capacity -= claimed.size();
            }
        } catch (StoreUnavailableException e) {
            LOG.warnf("Claim round skipped: %s", e.getMessage());
        }
    }

    /** A new leader resumes the FETCHING scans whose DT data was never stored (3.2 reconcile). */
    void onLeadership(@ObservesAsync LeadershipAcquired event) {
        try {
            List<ClaimedScan> pending = scans.fetchingWithoutFindings();
            if (!pending.isEmpty()) {
                LOG.infof("Resuming %d FETCHING scan(s)", pending.size());
            }
            pending.forEach(this::submit);
        } catch (StoreUnavailableException e) {
            LOG.warnf("Reconcile skipped: %s", e.getMessage());
        }
    }

    private void submit(ClaimedScan scan) {
        inflight.acquireUninterruptibly();
        workers.submit(() -> {
            try {
                fetch.run(scan);
            } catch (RuntimeException e) {
                // Stays FETCHING without findings; the next leader resumes it (onLeadership).
                LOG.errorf(e, "Scan %s: DT stage failed unexpectedly", scan.id());
            } finally {
                inflight.release();
            }
        });
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }
}
