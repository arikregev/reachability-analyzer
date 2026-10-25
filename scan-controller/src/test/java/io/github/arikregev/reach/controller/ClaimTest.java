package io.github.arikregev.reach.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.arikregev.reach.controller.leader.LeaderLock;
import io.github.arikregev.reach.controller.queue.ClaimLoop;
import io.github.arikregev.reach.controller.queue.Transitions;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The claim query of architecture.md 6.2: priority, aging, caps and not_before. */
@QuarkusTest
@QuarkusTestResource(WireMockDt.class)
@QuarkusTestResource(WireMockGitHub.class)
@QuarkusTestResource(S3Mock.class)
@WithKubernetesTestServer
class ClaimTest {

    @Inject
    TestDb db;

    @Inject
    Transitions transitions;

    @Inject
    ClaimLoop loop;

    @Inject
    LeaderLock leader;

    @BeforeEach
    void reset() {
        db.reset();
    }

    private List<UUID> claimIds(int batch) {
        return transitions.claim(batch).stream().map(ClaimedScan::id).toList();
    }

    @Test
    void higherWeightIsClaimedFirst() {
        UUID oldDefault = db.queue("acme/a", "default", 10);
        UUID freshPr = db.queue("acme/b", "pr", 0);
        UUID bulk = db.queue("acme/c", "bulk", 1);

        assertThat(claimIds(1)).containsExactly(freshPr);
        assertThat(claimIds(1)).containsExactly(oldDefault);
        assertThat(claimIds(1)).containsExactly(bulk);
        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void agingLetsALowClassOvertake() {
        // default 100 + 500 min * 1/min = 600 > pr 500 + ~0
        UUID agedDefault = db.queue("acme/a", "default", 500);
        db.queue("acme/b", "pr", 0);

        assertThat(claimIds(1)).containsExactly(agedDefault);
    }

    @Test
    void claimMovesTheScanToFetchingWithAnEvent() {
        UUID id = db.queue("acme/a", "default", 0);

        assertThat(claimIds(1)).containsExactly(id);

        assertThat(db.scan(id)).containsEntry("status", "FETCHING");
        assertThat(db.query("SELECT 1 FROM scan.scan_queue WHERE scan_id = ?", id)).isEmpty();
        assertThat(db.query("SELECT payload->>'status' AS s, payload->>'activity_id' AS a FROM scan.outbox WHERE key = ?",
                id.toString()))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("s", "FETCHING").containsEntry("a", "99"));
    }

    @Test
    void repoCapHoldsBackTheSameRepo() {
        db.update("INSERT INTO scan.concurrency_limit (scope, key, max_running) VALUES ('REPO', 'acme/a', 1)");
        UUID first = db.queue("acme/a", "pr", 0);
        UUID second = db.queue("acme/a", "pr", 0);
        UUID other = db.queue("acme/b", "default", 0);

        List<UUID> round1 = claimIds(1);
        assertThat(round1).containsAnyOf(first, second);
        assertThat(claimIds(1)).containsExactly(other);
        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void defaultRepoCapIsTwo() {
        db.queue("acme/a", "default", 0);
        db.queue("acme/a", "default", 0);
        db.queue("acme/a", "default", 0);

        assertThat(claimIds(1)).hasSize(1);
        assertThat(claimIds(1)).hasSize(1);
        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void orgCapApplies() {
        db.update("INSERT INTO scan.concurrency_limit (scope, key, max_running) VALUES ('ORG', 'acme', 1)");
        db.queue("acme/a", "default", 0);
        db.queue("acme/b", "default", 0);
        UUID otherOrg = db.queue("other/a", "default", 0);

        assertThat(claimIds(1)).hasSize(1);
        assertThat(claimIds(1)).containsExactly(otherOrg);
        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void classCapApplies() {
        db.update("UPDATE scan.priority_class SET max_running = 1 WHERE name = 'bulk'");
        db.queue("acme/a", "bulk", 0);
        db.queue("acme/b", "bulk", 0);

        assertThat(claimIds(1)).hasSize(1);
        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void disabledClassIsNotClaimed() {
        db.update("UPDATE scan.priority_class SET enabled = false WHERE name = 'bulk'");
        db.queue("acme/a", "bulk", 0);

        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void notBeforeInTheFutureWaits() {
        UUID id = db.queue("acme/a", "default", 0);
        db.update("UPDATE scan.scan_queue SET not_before = now() + interval '5 minutes' WHERE scan_id = ?", id);

        assertThat(claimIds(1)).isEmpty();
    }

    @Test
    void globalCapStopsTheLoop() {
        db.update("INSERT INTO scan.concurrency_limit (scope, key, max_running) VALUES ('GLOBAL', '*', 1)");
        UUID first = db.queue("acme/a", "default", 0);
        UUID second = db.queue("acme/b", "default", 0);
        leader.tick();

        loop.tick();

        long fetching = db.query("SELECT 1 FROM scan.scan WHERE status <> 'QUEUED' AND id IN (?, ?)", first, second).size();
        assertThat(fetching).isEqualTo(1);
    }
}
