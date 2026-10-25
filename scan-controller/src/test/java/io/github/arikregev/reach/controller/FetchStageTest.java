package io.github.arikregev.reach.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.leader.LeadershipAcquired;
import io.github.arikregev.reach.controller.queue.ClaimLoop;
import io.github.arikregev.reach.controller.queue.FetchStage;
import io.github.arikregev.reach.controller.queue.Transitions;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FETCHING against the mock Dependency-Track (architecture.md 8.1, 4.2 retry rules). */
@QuarkusTest
@QuarkusTestResource(WireMockDt.class)
class FetchStageTest {

    static final UUID PAYMENTS_UUID = UUID.fromString("0b6f0000-0000-4000-8000-000000000001");

    @Inject
    TestDb db;

    @Inject
    Transitions transitions;

    @Inject
    FetchStage fetch;

    @Inject
    ClaimLoop loop;

    @Inject
    LeaderLock leader;

    @Inject
    Event<LeadershipAcquired> leadership;

    @BeforeEach
    void reset() {
        db.reset();
        WireMockDt.server.resetRequests();
    }

    private ClaimedScan claimOnly() {
        List<ClaimedScan> claimed = transitions.claim(1);
        assertThat(claimed).hasSize(1);
        return claimed.getFirst();
    }

    private List<String> eventStatuses(UUID id) {
        return db.query("SELECT payload->>'status' AS s FROM scan.outbox WHERE key = ? ORDER BY id", id.toString())
                .stream().map(r -> (String) r.get("s")).toList();
    }

    @Test
    void loadsAllPagesOfFindingsWithTheKeyFromVault() throws SQLException {
        UUID id = db.queue("acme/payments", "default", 0);

        fetch.run(claimOnly());

        Map<String, Object> scan = db.scan(id);
        assertThat(scan).containsEntry("status", "FETCHING").containsEntry("dt_project_uuid", PAYMENTS_UUID);
        assertThat((String) scan.get("findings_hash")).hasSize(64);
        List<Map<String, Object>> findings = db.query(
                "SELECT vuln_id, aliases, purl, severity, repo FROM scan.finding WHERE scan_id = ? ORDER BY vuln_id", id);
        assertThat(findings).extracting(r -> r.get("vuln_id"))
                .containsExactly("CVE-2018-18074", "CVE-2021-44228", "GHSA-5ww9-j83m-q7qx");
        assertThat(Arrays.asList((Object[]) ((Array) findings.get(1).get("aliases")).getArray()))
                .containsExactly("GHSA-jfh8-c2jp-5v3q");
        assertThat(findings.get(1)).containsEntry("purl", "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1")
                .containsEntry("severity", "CRITICAL").containsEntry("repo", "acme/payments");
        // Two pages of page-size 2; the key is the one Vault Dev Services was seeded with.
        WireMockDt.server.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/finding/project/" + PAYMENTS_UUID))
                .withHeader("X-Api-Key", equalTo("test-dt-key")));
    }

    @Test
    void sameFindingsGiveTheSameHash() {
        UUID a = db.queue("acme/payments", "default", 0);
        fetch.run(claimOnly());
        db.update("UPDATE scan.scan SET status = 'SUCCEEDED' WHERE id = ?", a);
        UUID b = db.queue("acme/payments", "default", 0);
        fetch.run(claimOnly());

        assertThat(db.scan(a).get("findings_hash")).isEqualTo(db.scan(b).get("findings_hash"));
    }

    @Test
    void projectWithoutFindingsIsLoaded() {
        UUID id = db.queue("acme/empty", "default", 0);

        fetch.run(claimOnly());

        assertThat(db.scan(id).get("findings_hash")).isNotNull();
        assertThat(db.query("SELECT 1 FROM scan.finding WHERE scan_id = ?", id)).isEmpty();
    }

    @Test
    void unknownProjectFailsWithoutRetry() {
        UUID id = db.queue("acme/missing", "default", 0);

        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "FAILED").containsEntry("reason", "DT_PROJECT_NOT_FOUND");
        assertThat(eventStatuses(id)).containsExactly("FETCHING", "Failure");
        assertThat(db.query("SELECT payload->'unmapped'->'reachability'->>'reason' AS r, payload->>'type_uid' AS t "
                + "FROM scan.outbox WHERE key = ? AND payload->>'activity_id' = '6'", id.toString()))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("r", "DT_PROJECT_NOT_FOUND")
                        .containsEntry("t", "600706"));
    }

    @Test
    void uuidMismatchFails() {
        UUID id = db.queue("acme", "payments", "acme/payments", "main", UUID.randomUUID(), "default", 0);

        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "FAILED").containsEntry("reason", "DT_UUID_MISMATCH");
    }

    @Test
    void matchingUuidIsAccepted() {
        UUID id = db.queue("acme", "payments", "acme/payments", "main", PAYMENTS_UUID, "default", 0);

        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "FETCHING");
        assertThat(db.scan(id).get("findings_hash")).isNotNull();
    }

    @Test
    void dtOutageRequeuesWithBackoffThenFails() {
        UUID id = db.queue("acme/flaky", "pr", 30);
        Timestamp enqueued = (Timestamp) db.query("SELECT enqueued_at FROM scan.scan WHERE id = ?", id)
                .getFirst().get("enqueued_at");

        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "QUEUED").containsEntry("reason", "DT_UNAVAILABLE")
                .containsEntry("attempt", 2);
        Map<String, Object> queued = db.query("""
                SELECT attempt, priority_class, enqueued_at,
                       extract(epoch FROM not_before - now()) AS wait FROM scan.scan_queue WHERE scan_id = ?""", id)
                .getFirst();
        assertThat(queued).containsEntry("attempt", 2).containsEntry("priority_class", "pr");
        assertThat(queued.get("enqueued_at")).isEqualTo(enqueued);
        assertThat(((Number) queued.get("wait")).doubleValue()).isBetween(50.0, 61.0);
        assertThat(eventStatuses(id)).containsExactly("FETCHING", "QUEUED");

        // Attempt 2 waits 2 minutes; attempt 3 is the last one.
        db.update("UPDATE scan.scan_queue SET not_before = now() WHERE scan_id = ?", id);
        fetch.run(claimOnly());
        assertThat(((Number) db.query("SELECT extract(epoch FROM not_before - now()) AS w FROM scan.scan_queue "
                + "WHERE scan_id = ?", id).getFirst().get("w")).doubleValue()).isBetween(110.0, 121.0);
        db.update("UPDATE scan.scan_queue SET not_before = now() WHERE scan_id = ?", id);
        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "FAILED").containsEntry("reason", "DT_UNAVAILABLE")
                .containsEntry("attempt", 3);
        assertThat(eventStatuses(id)).containsExactly("FETCHING", "QUEUED", "FETCHING", "QUEUED", "FETCHING", "Failure");
    }

    @Test
    void claimLoopRunsTheFetchInTheBackground() {
        UUID id = db.queue("acme/payments", "default", 0);
        leader.tick();

        loop.tick();

        await().atMost(Duration.ofSeconds(10)).until(() -> db.scan(id).get("findings_hash") != null);
    }

    @Test
    void malformedDtAnswerIsRetriedLikeAnOutage() {
        UUID id = db.queue("acme/broken", "default", 0);

        fetch.run(claimOnly());

        assertThat(db.scan(id)).containsEntry("status", "QUEUED").containsEntry("reason", "DT_UNAVAILABLE");
    }

    @Test
    void sweepPicksUpScansStrandedInFetching() throws InterruptedException {
        // Lead first, and let a first-leadership resume (which ignores claim age) finish before the scans exist.
        leader.tick();
        Thread.sleep(500);
        UUID stranded = db.queue("acme/payments", "default", 0);
        claimOnly();
        UUID fresh = db.queue("acme/payments", "default", 0);
        claimOnly();
        // As if the fetch died after the DT calls (a Postgres blip): FETCHING, no findings, claimed long ago.
        db.update("UPDATE scan.scan SET claimed_at = now() - interval '10 minutes' WHERE id = ?", stranded);

        loop.sweep();

        await().atMost(Duration.ofSeconds(10)).until(() -> db.scan(stranded).get("findings_hash") != null);
        assertThat(db.scan(fresh).get("findings_hash")).isNull();
    }

    @Test
    void newLeaderResumesFetchingScans() {
        UUID id = db.queue("acme/payments", "default", 0);
        claimOnly();
        assertThat(db.scan(id).get("findings_hash")).isNull();

        leadership.fireAsync(new LeadershipAcquired());

        await().atMost(Duration.ofSeconds(10)).until(() -> db.scan(id).get("findings_hash") != null);
    }
}
