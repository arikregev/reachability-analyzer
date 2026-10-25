package io.github.arikregev.reach.controller.jobs;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.github.GitHubApp;
import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.leader.LeadershipAcquired;
import io.github.arikregev.reach.controller.leader.LeadershipLost;
import io.github.arikregev.reach.controller.queue.Transitions;
import io.github.arikregev.reach.controller.s3.S3Storage;
import io.github.arikregev.reach.controller.s3.ScanKeys;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.github.arikregev.reach.controller.store.ScanStore;
import io.github.arikregev.reach.controller.store.StoreUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Follows the controller's Jobs with a SharedInformer per namespace while this replica leads (architecture.md 3.2):
 * <ul>
 * <li>fetch Complete: tarball present, so the scan Job is created and the scan moves to SCANNING;</li>
 * <li>scan Complete: {@code summary.json} present, so the scan moves to PUBLISHING (validation comes with the
 * outputs);</li>
 * <li>Failed: the Job's condition reason and the fetch exit code give the reason code (5.4a f, D-036); a failed
 * scan Job that still wrote {@code summary.json} counts as finished, since the summary may be partial (4.3);</li>
 * <li>a finished fetch Job's installation token is revoked and its Secret deleted;</li>
 * <li>a Job whose scan is cancelled, finished or on a newer attempt is deleted.</li>
 * </ul>
 * Every action is guarded by the scan's current status and attempt, so repeated events and resyncs are harmless.
 */
@ApplicationScoped
public class JobWatcher {

    private static final Logger LOG = Logger.getLogger(JobWatcher.class);

    /** source-fetch exit codes (images/source-fetch/fetch.sh). */
    static final Map<Integer, ReasonCode> FETCH_EXIT_CODES = Map.of(
            10, ReasonCode.COMMIT_NOT_FOUND,
            11, ReasonCode.SOURCE_TOO_LARGE,
            12, ReasonCode.GITHUB_UNAVAILABLE,
            13, ReasonCode.S3_UNAVAILABLE);

    private static final Set<ScanStatus> TERMINAL =
            EnumSet.of(ScanStatus.SUCCEEDED, ScanStatus.PARTIAL, ScanStatus.FAILED, ScanStatus.CANCELLED);

    private final KubernetesClient client;
    private final LeaderLock leader;
    private final ScanStore scans;
    private final Transitions transitions;
    private final JobLauncher launcher;
    private final S3Storage s3;
    private final GitHubApp github;
    private final ControllerConfig config;
    private final MeterRegistry registry;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final Map<String, Job> latest = new ConcurrentHashMap<>();
    private final List<SharedIndexInformer<Job>> informers = new ArrayList<>();

    public JobWatcher(KubernetesClient client, LeaderLock leader, ScanStore scans, Transitions transitions,
                      JobLauncher launcher, S3Storage s3, GitHubApp github, ControllerConfig config,
                      MeterRegistry registry) {
        this.client = client;
        this.leader = leader;
        this.scans = scans;
        this.transitions = transitions;
        this.launcher = launcher;
        this.s3 = s3;
        this.github = github;
        this.config = config;
        this.registry = registry;
    }

    void onLeadership(@ObservesAsync LeadershipAcquired event) {
        if (config.job().enabled()) {
            start();
            reconcileScanning();
        }
    }

    void onLeadershipLost(@ObservesAsync LeadershipLost event) {
        stop();
    }

    /** Starts the informers and waits for their first list, which replays every live Job as an add. */
    public synchronized void start() {
        if (!informers.isEmpty()) {
            return;
        }
        long resync = config.job().resync().toMillis();
        for (String ns : namespaces()) {
            SharedIndexInformer<Job> informer = client.batch().v1().jobs().inNamespace(ns)
                    .withLabel(JobSpecs.MANAGED_BY, JobSpecs.MANAGER)
                    .runnableInformer(resync);
            informer.addEventHandler(new ResourceEventHandler<>() {
                @Override
                public void onAdd(Job job) {
                    submit(job);
                }

                @Override
                public void onUpdate(Job old, Job job) {
                    submit(job);
                }

                @Override
                public void onDelete(Job job, boolean finalStateUnknown) {
                    // Deleted by TTL or by us; the scan's state is already past this Job.
                }
            });
            try {
                informer.start().toCompletableFuture().get(1, TimeUnit.MINUTES);
            } catch (Exception e) {
                LOG.warnf("Job informer in %s did not sync: %s", ns, e.getMessage());
            }
            informers.add(informer);
        }
        LOG.infof("Watching Jobs in %s", namespaces());
    }

    private List<String> namespaces() {
        return List.of(config.job().fetch().namespace(), config.job().scan().namespace()).stream().distinct().toList();
    }

    public synchronized void stop() {
        informers.forEach(SharedIndexInformer::stop);
        informers.clear();
    }

    @PreDestroy
    void close() {
        stop();
        workers.shutdownNow();
    }

    /**
     * SCANNING scans whose scan Job is gone (deleted by TTL while no controller led): a summary in S3 still counts,
     * otherwise the scan fails with INVALID_SUMMARY. Runs only once every informer has synced, and a Job missing
     * from the cache is confirmed with a direct read before anything fails. Runs at leadership and every
     * {@code reach.job.resync}. Public for tests.
     */
    @Scheduled(every = "{reach.job.resync}", delayed = "{reach.job.resync}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void reconcileScanning() {
        if (!leader.isLeader() || !config.job().enabled() || !synced()) {
            return;
        }
        try {
            for (ClaimedScan scan : scans.inStatus(ScanStatus.SCANNING)) {
                String name = JobSpecs.name(Stage.SCAN, scan.id(), scan.attempt());
                if (cached(config.job().scan().namespace() + "/" + name) || launcher.exists(Stage.SCAN, scan)) {
                    continue;
                }
                LOG.infof("Scan %s: scan Job %s is gone; reconciling from S3", scan.id(), name);
                scanFinished(scan, "scan Job " + name + " no longer exists");
            }
        } catch (StoreUnavailableException | JobLauncher.JobLaunchException e) {
            LOG.warnf("SCANNING reconcile skipped: %s", e.getMessage());
        }
    }

    private synchronized boolean synced() {
        return !informers.isEmpty() && informers.stream().allMatch(SharedIndexInformer::hasSynced);
    }

    private synchronized boolean cached(String key) {
        return informers.stream().anyMatch(i -> i.getIndexer().getByKey(key) != null);
    }

    /** Events for one Job are handled one at a time; only the newest pending state is kept. */
    private void submit(Job job) {
        String key = job.getMetadata().getNamespace() + "/" + job.getMetadata().getName();
        latest.put(key, job);
        if (inflight.add(key)) {
            workers.submit(() -> drain(key));
        }
    }

    private void drain(String key) {
        try {
            Job job;
            while ((job = latest.remove(key)) != null) {
                try {
                    handle(job);
                } catch (RuntimeException e) {
                    // The next update or resync retries.
                    LOG.errorf(e, "Job %s: handling failed", key);
                }
            }
        } finally {
            inflight.remove(key);
            if (latest.containsKey(key) && inflight.add(key)) {
                workers.submit(() -> drain(key));
            }
        }
    }

    /** One Job event. Public for tests. */
    public void handle(Job job) {
        if (!leader.isLeader()) {
            return;
        }
        Map<String, String> labels = job.getMetadata().getLabels();
        Stage stage = Stage.of(labels.get(JobSpecs.STAGE));
        UUID scanId = UUID.fromString(labels.get(JobSpecs.SCAN_ID));
        int attempt = Integer.parseInt(labels.get(JobSpecs.ATTEMPT));
        Optional<String> outcome = outcome(job);

        Optional<ScanStore.Current> found = scans.find(scanId);
        if (found.isEmpty()) {
            return;
        }
        ClaimedScan scan = found.get().scan();
        ScanStatus status = found.get().status();
        boolean stale = TERMINAL.contains(status) || attempt != scan.attempt();
        if (stage == Stage.FETCH && (stale || outcome.isPresent())) {
            releaseToken(scan, attempt);
        }
        if (stale) {
            if (outcome.isEmpty()) {
                LOG.infof("Scan %s is %s (attempt %d); deleting its %s Job of attempt %d", scanId, status,
                        scan.attempt(), stage.label(), attempt);
                launcher.delete(stage, scan, attempt);
            }
            return;
        }
        if (status != stage.runsIn() || outcome.isEmpty()) {
            return;
        }
        registry.counter("reach.jobs.finished", "stage", stage.label(), "result", outcome.get()).increment();
        if (outcome.get().equals("Complete")) {
            if (stage == Stage.FETCH) {
                fetchFinished(scan);
            } else {
                scanFinished(scan, "scan Job completed without summary.json");
            }
            return;
        }
        Failure f = failure(stage, job);
        LOG.warnf("Scan %s: %s Job failed: %s", scanId, stage.label(), f.detail());
        if (stage == Stage.SCAN && f.reason() == ReasonCode.INVALID_SUMMARY) {
            // An engine crash or OOM after the summary was written still publishes what is there (partial results).
            scanFinished(scan, f.detail());
            return;
        }
        transitions.failed(scan, stage.runsIn(), f.reason(), f.detail());
    }

    /**
     * Revokes the fetch Job's installation token and deletes its Secret once the Job has finished or is stale. Best
     * effort: the token expires within the hour anyway. A resumed fetch mints a new token. Repeated events find no
     * Secret and do nothing.
     */
    private void releaseToken(ClaimedScan scan, int attempt) {
        try {
            Optional<String> token = launcher.fetchToken(scan, attempt);
            if (token.isEmpty()) {
                return;
            }
            github.revoke(token.get());
            launcher.deleteSecret(Stage.FETCH, scan, attempt);
        } catch (RuntimeException e) {
            LOG.warnf("Scan %s: releasing the fetch token of attempt %d failed: %s", scan.id(), attempt,
                    e.getMessage());
        }
    }

    private void fetchFinished(ClaimedScan scan) {
        ScanKeys keys = JobLauncher.keys(scan);
        try {
            if (s3.size(keys.sourceTarball()).isEmpty()) {
                transitions.failed(scan, ScanStatus.FETCHING, ReasonCode.S3_UNAVAILABLE,
                        "fetch Job completed but " + keys.sourceTarball() + " is missing");
                return;
            }
            launcher.launchScan(scan);
        } catch (S3Storage.S3UnavailableException e) {
            transitions.failed(scan, ScanStatus.FETCHING, e.reason(), e.getMessage());
            return;
        } catch (JobLauncher.JobLaunchException e) {
            transitions.failed(scan, ScanStatus.FETCHING, e.reason(), e.getMessage());
            return;
        }
        if (!transitions.advance(scan, ScanStatus.FETCHING, ScanStatus.SCANNING)) {
            launcher.delete(Stage.SCAN, scan, scan.attempt());
        }
    }

    private void scanFinished(ClaimedScan scan, String missingDetail) {
        ScanKeys keys = JobLauncher.keys(scan);
        try {
            if (s3.size(keys.summary()).isPresent()) {
                transitions.advance(scan, ScanStatus.SCANNING, ScanStatus.PUBLISHING);
            } else {
                transitions.failed(scan, ScanStatus.SCANNING, ReasonCode.INVALID_SUMMARY, missingDetail);
            }
        } catch (S3Storage.S3UnavailableException e) {
            // Retried on the next resync; the summary may well be there.
            LOG.warnf("Scan %s: %s", scan.id(), e.getMessage());
        }
    }

    /** "Complete", "Failed" or empty while the Job runs. */
    static Optional<String> outcome(Job job) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) {
            return Optional.empty();
        }
        return job.getStatus().getConditions().stream()
                .filter(c -> "True".equals(c.getStatus()))
                .map(JobCondition::getType)
                .filter(t -> t.equals("Complete") || t.equals("Failed"))
                .findFirst();
    }

    record Failure(ReasonCode reason, String detail) {
    }

    Failure failure(Stage stage, Job job) {
        JobCondition failed = job.getStatus().getConditions().stream()
                .filter(c -> "Failed".equals(c.getType()) && "True".equals(c.getStatus()))
                .findFirst().orElseThrow();
        String why = failed.getReason() == null ? "" : failed.getReason();
        String message = failed.getMessage() == null ? why : why + ": " + failed.getMessage();
        if (why.equals("DeadlineExceeded")) {
            return new Failure(ReasonCode.DEADLINE_EXCEEDED, stage.label() + " Job exceeded activeDeadlineSeconds");
        }
        if (why.equals("PodFailurePolicy")) {
            return new Failure(ReasonCode.POD_DISRUPTED, stage.label() + " pod was disrupted (" + message + ")");
        }
        Optional<Integer> exit = exitCode(job);
        String detail = stage.label() + " Job failed" + exit.map(c -> " with exit code " + c).orElse("")
                + (message.isEmpty() ? "" : " (" + message + ")");
        if (stage == Stage.FETCH) {
            return new Failure(exit.map(FETCH_EXIT_CODES::get).orElse(ReasonCode.GITHUB_UNAVAILABLE), detail);
        }
        return new Failure(ReasonCode.INVALID_SUMMARY, detail);
    }

    private Optional<Integer> exitCode(Job job) {
        try {
            List<Pod> pods = client.pods().inNamespace(job.getMetadata().getNamespace())
                    .withLabel(JobSpecs.JOB_NAME, job.getMetadata().getName()).list().getItems();
            return pods.stream()
                    .filter(p -> p.getStatus() != null && p.getStatus().getContainerStatuses() != null)
                    .flatMap(p -> p.getStatus().getContainerStatuses().stream())
                    .map(ContainerStatus::getState)
                    .filter(s -> s != null && s.getTerminated() != null)
                    .map(s -> s.getTerminated().getExitCode())
                    .filter(c -> c != null && c != 0)
                    .findFirst();
        } catch (KubernetesClientException e) {
            LOG.debugf("Pods of %s not readable: %s", job.getMetadata().getName(), e.getMessage());
            return Optional.empty();
        }
    }
}
