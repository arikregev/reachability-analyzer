package io.github.arikregev.reach.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.arikregev.reach.controller.jobs.JobSpecs;
import io.github.arikregev.reach.controller.jobs.JobWatcher;
import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.queue.FetchStage;
import io.github.arikregev.reach.controller.queue.Transitions;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FETCHING through SCANNING to PUBLISHING against the mocks (architecture.md 3.3, 3.4, 4.2, 9): S3 (s3mock), GitHub
 * (WireMock) and the Kubernetes API (Fabric8 mock server in CRUD mode). Job outcomes are simulated by writing the
 * Job status, which the watcher's informer picks up.
 */
@QuarkusTest
@QuarkusTestResource(WireMockDt.class)
@QuarkusTestResource(WireMockGitHub.class)
@QuarkusTestResource(S3Mock.class)
@WithKubernetesTestServer
class JobsTest {

    static final String NS = "reach-test";
    static final Duration WAIT = Duration.ofSeconds(15);

    @Inject
    TestDb db;

    @Inject
    Transitions transitions;

    @Inject
    FetchStage fetch;

    @Inject
    LeaderLock leader;

    @Inject
    JobWatcher watcher;

    @Inject
    io.github.arikregev.reach.controller.queue.ClaimLoop loop;

    @Inject
    KubernetesClient k8s;

    @Inject
    ObjectMapper mapper;

    @BeforeEach
    void reset() {
        db.reset();
        WireMockGitHub.server.resetRequests();
        leader.tick();
        watcher.start();
    }

    private ClaimedScan claimOnly() {
        List<ClaimedScan> claimed = transitions.claim(1);
        assertThat(claimed).hasSize(1);
        return claimed.getFirst();
    }

    /** Each test repo has its own DT project with the payments findings, so the D-037 binding check passes. */
    private UUID fetched(String repo) {
        UUID id = db.queue("acme", repo, "acme/" + repo, "main", null, "default", 0);
        fetch.run(claimOnly());
        return id;
    }

    private Job job(String stage, UUID id, int attempt) {
        return k8s.batch().v1().jobs().inNamespace(NS).withName("reach-" + stage + "-" + id + "-" + attempt).get();
    }

    private Map<String, String> secret(String stage, UUID id, int attempt) {
        Secret s = k8s.secrets().inNamespace(NS).withName("reach-" + stage + "-" + id + "-" + attempt).get();
        return s.getData().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                e -> new String(Base64.getDecoder().decode(e.getValue()), StandardCharsets.UTF_8)));
    }

    private void finish(String stage, UUID id, int attempt, String type, String reason) {
        k8s.batch().v1().jobs().inNamespace(NS).withName("reach-" + stage + "-" + id + "-" + attempt)
                .editStatus(j -> {
                    j.setStatus(new JobStatusBuilder().withConditions(new JobConditionBuilder()
                            .withType(type).withStatus("True").withReason(reason).withMessage("simulated").build())
                            .build());
                    return j;
                });
    }

    private void failedPod(String stage, UUID id, int attempt, int exitCode) {
        String job = "reach-" + stage + "-" + id + "-" + attempt;
        k8s.pods().inNamespace(NS).resource(new PodBuilder()
                .withNewMetadata().withName(job + "-x").withNamespace(NS)
                    .addToLabels(JobSpecs.JOB_NAME, job).endMetadata()
                .withNewStatus().withPhase("Failed").addNewContainerStatus().withName(stage)
                    .withNewState().withNewTerminated().withExitCode(exitCode).endTerminated().endState()
                .endContainerStatus().endStatus()
                .build()).create();
    }

    /** A work file of attempt 1. */
    private String key(UUID id, String file) {
        return "work/" + id + "/1/" + file;
    }

    private String summaryKey(UUID id) {
        return key(id, "summary.json");
    }

    private List<String> eventStatuses(UUID id) {
        return db.query("SELECT payload->>'status' AS s FROM scan.outbox WHERE key = ? ORDER BY id", id.toString())
                .stream().map(r -> (String) r.get("s")).toList();
    }

    @Test
    void fetchWritesTheInputBundleAndLaunchesAHardenedFetchJob() throws Exception {
        UUID id = fetched("payments");

        JsonNode bundle = mapper.readTree(S3Mock.get(key(id, "input.json")));
        assertThat(bundle.path("schema").asText()).isEqualTo("reach.input.v1");
        assertThat(bundle.path("repo").path("commit").asText()).isEqualTo("4f1c9e2b7d3a8e6f0b5c1d2e3f4a5b6c7d8e9f0a");
        assertThat(bundle.path("findings")).hasSize(3);
        assertThat(bundle.path("sbom").path("specVersion").asText()).isEqualTo("1.6");

        Job job = job("fetch", id, 1);
        assertThat(job.getMetadata().getLabels()).containsEntry(JobSpecs.SCAN_ID, id.toString())
                .containsEntry(JobSpecs.STAGE, "fetch").containsEntry(JobSpecs.ATTEMPT, "1");
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(600L);
        assertThat(job.getSpec().getTtlSecondsAfterFinished()).isEqualTo(600);
        assertThat(job.getSpec().getPodFailurePolicy().getRules().getFirst().getAction()).isEqualTo("FailJob");
        var pod = job.getSpec().getTemplate().getSpec();
        assertThat(pod.getRestartPolicy()).isEqualTo("Never");
        assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getServiceAccountName()).isEqualTo("reach-fetch");
        var container = pod.getContainers().getFirst();
        assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
        assertThat(container.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
        assertThat(container.getSecurityContext().getCapabilities().getDrop()).containsExactly("ALL");
        assertThat(container.getResources().getLimits()).containsKeys("cpu", "memory", "ephemeral-storage");
        assertThat(container.getEnv()).anySatisfy(e -> assertThat(e.getName()).isEqualTo("GIT_HOST"));
        assertThat(pod.getVolumes()).extracting(v -> v.getName()).containsExactly("secrets", "work", "tmp", "home");

        Map<String, String> files = secret("fetch", id, 1);
        assertThat(files).containsEntry("github-token", "ghs_mock_installation_token");
        assertThat(files.get("source-put-url")).contains(key(id, "source.tar.zst")).contains("X-Amz-Signature");
        Secret s = k8s.secrets().inNamespace(NS).withName(job.getMetadata().getName()).get();
        assertThat(s.getMetadata().getOwnerReferences()).singleElement()
                .satisfies(r -> assertThat(r.getName()).isEqualTo(job.getMetadata().getName()));
        assertThat(db.scan(id)).containsEntry("status", "FETCHING");
    }

    @Test
    void theAppJwtIsSignedWithTheAppKey() throws Exception {
        fetched("payments");

        String auth = WireMockGitHub.server.findAll(postRequestedFor(urlPathEqualTo("/app/installations/4242/access_tokens")))
                .getFirst().getHeader("Authorization");
        String[] jwt = auth.substring("Bearer ".length()).split("\\.");
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(WireMockGitHub.appKey.getPublic());
        rsa.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(rsa.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
        assertThat(new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8)).contains("\"iss\":\"12345\"");
    }

    @Test
    void repoTheAppCannotSeeFails() {
        UUID id = fetched("not-installed");

        assertThat(db.scan(id)).containsEntry("status", "FAILED").containsEntry("reason", "COMMIT_NOT_FOUND");
        assertThat(job("fetch", id, 1)).isNull();
    }

    @Test
    void unknownCommitFails() {
        UUID id = db.queue("acme", "payments", "acme/payments", "main", null, "default", 0);
        db.update("UPDATE scan.scan SET commit_sha = repeat('0', 40) WHERE id = ?", id);
        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "FAILED").containsEntry("reason", "COMMIT_NOT_FOUND");
    }

    @Test
    void gitHubOutageIsRetried() {
        UUID id = fetched("github-down");

        assertThat(db.scan(id)).containsEntry("status", "QUEUED").containsEntry("reason", "GITHUB_UNAVAILABLE")
                .containsEntry("attempt", 2);
    }

    @Test
    void finishedFetchLaunchesTheScanJob() {
        UUID id = fetched("payments");
        S3Mock.put(key(id, "source.tar.zst"), "tarball");

        finish("fetch", id, 1, "Complete", null);

        await().atMost(WAIT).until(() -> "SCANNING".equals(db.scan(id).get("status")));
        Job scan = job("scan", id, 1);
        assertThat(scan.getSpec().getTemplate().getSpec().getServiceAccountName()).isEqualTo("reach-scan");
        assertThat(scan.getSpec().getActiveDeadlineSeconds()).isEqualTo(2700L);
        Map<String, String> files = secret("scan", id, 1);
        assertThat(files.get("source-get-url")).contains(key(id, "source.tar.zst"));
        assertThat(files.get("input-get-url")).contains(key(id, "input.json"));
        assertThat(files.get("summary-put-url")).contains(summaryKey(id)).doesNotContain("acme/payments");
        assertThat(eventStatuses(id)).containsExactly("FETCHING", "SCANNING");
    }

    @Test
    void finishedFetchRevokesItsTokenAndDeletesItsSecret() {
        UUID id = scanning("payments");

        await().atMost(WAIT).until(() -> k8s.secrets().inNamespace(NS).withName("reach-fetch-" + id + "-1").get() == null);
        assertThat(WireMockGitHub.server.findAll(deleteRequestedFor(urlPathEqualTo("/installation/token"))
                .withHeader("Authorization", equalTo("Bearer ghs_mock_installation_token")))).hasSize(1);
        assertThat(k8s.secrets().inNamespace(NS).withName("reach-scan-" + id + "-1").get()).isNotNull();
    }

    @Test
    void finishedFetchWithoutTarballIsRetried() {
        UUID id = fetched("payments");

        finish("fetch", id, 1, "Complete", null);

        await().atMost(WAIT).until(() -> "QUEUED".equals(db.scan(id).get("status")));
        assertThat(db.scan(id)).containsEntry("reason", "S3_UNAVAILABLE");
    }

    @Test
    void fetchExitCodesMapToReasonCodes() {
        UUID notFound = fetched("payments");
        failedPod("fetch", notFound, 1, 10);
        finish("fetch", notFound, 1, "Failed", "BackoffLimitExceeded");
        await().atMost(WAIT).until(() -> "FAILED".equals(db.scan(notFound).get("status")));
        assertThat(db.scan(notFound)).containsEntry("reason", "COMMIT_NOT_FOUND");
        assertThat((String) db.scan(notFound).get("reason_detail")).contains("exit code 10");

        UUID tooLarge = fetched("payments");
        failedPod("fetch", tooLarge, 1, 11);
        finish("fetch", tooLarge, 1, "Failed", "BackoffLimitExceeded");
        await().atMost(WAIT).until(() -> "FAILED".equals(db.scan(tooLarge).get("status")));
        assertThat(db.scan(tooLarge)).containsEntry("reason", "SOURCE_TOO_LARGE");

        UUID outage = fetched("payments");
        failedPod("fetch", outage, 1, 12);
        finish("fetch", outage, 1, "Failed", "BackoffLimitExceeded");
        await().atMost(WAIT).until(() -> "QUEUED".equals(db.scan(outage).get("status")));
        assertThat(db.scan(outage)).containsEntry("reason", "GITHUB_UNAVAILABLE");
    }

    private UUID scanning(String repo) {
        UUID id = fetched(repo);
        S3Mock.put(key(id, "source.tar.zst"), "tarball");
        finish("fetch", id, 1, "Complete", null);
        await().atMost(WAIT).until(() -> "SCANNING".equals(db.scan(id).get("status")));
        return id;
    }

    @Test
    void finishedScanWithSummaryMovesToPublishing() {
        UUID id = scanning("payments");
        S3Mock.put(summaryKey(id), "{}");

        finish("scan", id, 1, "Complete", null);

        await().atMost(WAIT).until(() -> "PUBLISHING".equals(db.scan(id).get("status")));
        assertThat(eventStatuses(id)).containsExactly("FETCHING", "SCANNING", "PUBLISHING");
    }

    @Test
    void finishedScanWithoutSummaryFails() {
        UUID id = scanning("payments");

        finish("scan", id, 1, "Complete", null);

        await().atMost(WAIT).until(() -> "FAILED".equals(db.scan(id).get("status")));
        assertThat(db.scan(id)).containsEntry("reason", "INVALID_SUMMARY");
    }

    @Test
    void failedScanThatWroteASummaryStillPublishes() {
        UUID id = scanning("payments");
        S3Mock.put(summaryKey(id), "{}");

        // For example an engine OOM after the summary was written.
        finish("scan", id, 1, "Failed", "BackoffLimitExceeded");

        await().atMost(WAIT).until(() -> "PUBLISHING".equals(db.scan(id).get("status")));
    }

    @Test
    void failedScanWithoutSummaryFails() {
        UUID id = scanning("payments");

        finish("scan", id, 1, "Failed", "BackoffLimitExceeded");

        await().atMost(WAIT).until(() -> "FAILED".equals(db.scan(id).get("status")));
        assertThat(db.scan(id)).containsEntry("reason", "INVALID_SUMMARY");
    }

    @Test
    void scanDeadlineFails() {
        UUID id = scanning("payments");

        finish("scan", id, 1, "Failed", "DeadlineExceeded");

        await().atMost(WAIT).until(() -> "FAILED".equals(db.scan(id).get("status")));
        assertThat(db.scan(id)).containsEntry("reason", "DEADLINE_EXCEEDED");
    }

    @Test
    void disruptedScanIsRequeuedAndTheNextAttemptGetsNewJobs() {
        UUID id = scanning("payments");

        finish("scan", id, 1, "Failed", "PodFailurePolicy");

        await().atMost(WAIT).until(() -> "QUEUED".equals(db.scan(id).get("status")));
        assertThat(db.scan(id)).containsEntry("reason", "POD_DISRUPTED").containsEntry("attempt", 2);
        assertThat(db.query("SELECT attempt FROM scan.scan_queue WHERE scan_id = ?", id)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("attempt", 2));

        db.update("UPDATE scan.scan_queue SET not_before = now() WHERE scan_id = ?", id);
        fetch.run(claimOnly());
        assertThat(job("fetch", id, 2)).isNotNull();
    }

    @Test
    void sweepRelaunchesAFetchJobThatNeverAppeared() {
        UUID id = fetched("payments");
        k8s.batch().v1().jobs().inNamespace(NS).withName("reach-fetch-" + id + "-1").delete();
        await().atMost(WAIT).until(() -> job("fetch", id, 1) == null);
        db.update("UPDATE scan.scan SET claimed_at = now() - interval '10 minutes' WHERE id = ?", id);

        loop.sweep();

        await().atMost(WAIT).until(() -> job("fetch", id, 1) != null);
        assertThat(db.scan(id)).containsEntry("status", "FETCHING");
    }

    @Test
    void cancellingDeletesTheRunningJob() {
        UUID id = fetched("payments");
        db.update("UPDATE scan.scan SET status = 'CANCELLED' WHERE id = ?", id);

        // Any event for the Job (here a status change without an outcome) lets the watcher see the cancel.
        k8s.batch().v1().jobs().inNamespace(NS).withName("reach-fetch-" + id + "-1")
                .editStatus(j -> {
                    j.setStatus(new JobStatusBuilder().withActive(1).build());
                    return j;
                });

        await().atMost(WAIT).until(() -> job("fetch", id, 1) == null);
    }

    @Test
    void newLeaderReconcilesScanningScansWhoseJobIsGone() {
        UUID done = scanning("payments");
        UUID lost = scanning("payments");
        k8s.batch().v1().jobs().inNamespace(NS).withName("reach-scan-" + done + "-1").delete();
        k8s.batch().v1().jobs().inNamespace(NS).withName("reach-scan-" + lost + "-1").delete();
        S3Mock.put(summaryKey(done), "{}");
        await().atMost(WAIT).until(() -> job("scan", done, 1) == null && job("scan", lost, 1) == null);
        watcher.stop();

        // No informer, so no synced cache: nothing may be failed on an empty view of the cluster.
        watcher.reconcileScanning();
        assertThat(db.scan(done)).containsEntry("status", "SCANNING");
        assertThat(db.scan(lost)).containsEntry("status", "SCANNING");

        watcher.start();
        watcher.reconcileScanning();

        assertThat(db.scan(done)).containsEntry("status", "PUBLISHING");
        assertThat(db.scan(lost)).containsEntry("status", "FAILED").containsEntry("reason", "INVALID_SUMMARY");
    }
}
