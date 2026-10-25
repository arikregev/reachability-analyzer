package io.github.arikregev.reach.controller.queue;

import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.jobs.JobLauncher;
import io.github.arikregev.reach.controller.jobs.Stage;
import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.leader.LeadershipAcquired;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.github.arikregev.reach.controller.store.ScanStore;
import io.github.arikregev.reach.controller.store.StoreUnavailableException;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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
    private final JobLauncher jobs;
    private final ControllerConfig config;
    private final Semaphore inflight;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ClaimLoop(LeaderLock leader, ScanStore scans, Transitions transitions, FetchStage fetch,
                     JobLauncher jobs, ControllerConfig config) {
        this.jobs = jobs;
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

    /**
     * A new leader re-runs FETCHING for every scan left in it (3.2 reconcile). Each step is idempotent: findings are
     * replaced, the bundle is rewritten and an existing fetch Job is kept; the Job watcher then picks up its result.
     */
    void onLeadership(@ObservesAsync LeadershipAcquired event) {
        // A scan whose fetch Job exists is past the controller's part; the JobWatcher follows it.
        resume(() -> scans.inStatus(ScanStatus.FETCHING).stream()
                .filter(s -> !config.job().enabled() || !jobs.exists(Stage.FETCH, s))
                .toList(), "Resuming");
    }

    /**
     * The leader's sweep for scans stranded in FETCHING by an unexpected error (a Postgres blip after the DT calls,
     * for example): claimed longer than {@code reach.queue.fetch-timeout} ago, not being worked on here, and with
     * no fetch Job for their attempt (with Jobs off: no DT data stored yet).
     */
    @Scheduled(every = "{reach.queue.sweep-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        if (!leader.isLeader()) {
            return;
        }
        Duration age = config.queue().fetchTimeout();
        if (config.job().enabled()) {
            resume(() -> scans.fetching(age, false).stream()
                    .filter(s -> !running.contains(s.id()) && !jobs.exists(Stage.FETCH, s))
                    .toList(), "Sweeping");
        } else {
            resume(() -> scans.fetching(age, true), "Sweeping");
        }
    }

    private void resume(Supplier<List<ClaimedScan>> query, String what) {
        try {
            List<ClaimedScan> pending = query.get().stream().filter(s -> !running.contains(s.id())).toList();
            if (!pending.isEmpty()) {
                LOG.infof("%s %d FETCHING scan(s)", what, pending.size());
            }
            pending.forEach(this::submit);
        } catch (StoreUnavailableException e) {
            LOG.warnf("Reconcile skipped: %s", e.getMessage());
        } catch (JobLauncher.JobLaunchException e) {
            LOG.warnf("Sweep skipped: %s", e.getMessage());
        }
    }

    /**
     * Runs FETCHING for one scan unless this replica already is (a re-acquired leadership or a sweep can see a scan
     * whose fetch is still running). Blocks the caller until a DT worker is free.
     */
    private void submit(ClaimedScan scan) {
        if (!running.add(scan.id())) {
            return;
        }
        inflight.acquireUninterruptibly();
        workers.submit(() -> {
            try {
                fetch.run(scan);
            } catch (RuntimeException e) {
                // Stays FETCHING; the sweep or the next leader resumes it.
                LOG.errorf(e, "Scan %s: FETCHING failed unexpectedly", scan.id());
            } finally {
                running.remove(scan.id());
                inflight.release();
            }
        });
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }
}
