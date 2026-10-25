package io.github.arikregev.reach.controller.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** The {@code reach.*} keys read by scan-controller (architecture.md section 12). */
@ConfigMapping(prefix = "reach")
public interface ControllerConfig {

    Queue queue();

    Retry retry();

    Dt dt();

    Outbox outbox();

    Leader leader();

    Schema schema();

    interface Queue {
        /** Used when {@code scan.concurrency_limit} has no GLOBAL row (D-020). */
        @WithDefault("170")
        int globalMaxRunning();

        /** Per-org cap when the org has no ORG row; unset means no cap. */
        Optional<Integer> orgMaxRunning();

        /** Per-repo cap when the repo has no REPO row. */
        @WithDefault("2")
        int repoMaxRunning();

        @WithDefault("1")
        int claimBatch();

        @WithDefault("PT2S")
        Duration claimInterval();
    }

    interface Retry {
        @WithDefault("3")
        int maxAttempts();

        /** Base of the exponential backoff: attempt n waits backoff * 2^(n-1). */
        @WithDefault("PT1M")
        Duration backoff();
    }

    interface Dt {
        @WithDefault("100")
        int pageSize();

        /** Scans whose DT data is loaded in parallel. */
        @WithDefault("8")
        int maxConcurrent();
    }

    interface Outbox {
        @WithDefault("100")
        int batchSize();

        /** Published rows older than this are deleted. */
        @WithDefault("P1D")
        Duration retention();
    }

    interface Leader {
        /** Postgres advisory lock key held by the active controller (D-006). */
        @WithDefault("7304110001")
        long lockKey();
    }

    interface Schema {
        /** Fail start-up while the Liquibase changelog has changesets reachability-api has not applied (D-031). */
        @WithDefault("true")
        boolean checkAtStart();
    }
}
