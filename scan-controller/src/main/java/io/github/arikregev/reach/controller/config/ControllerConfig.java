package io.github.arikregev.reach.controller.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
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

    S3 s3();

    Github github();

    Job job();

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

        /**
         * A FETCHING scan without stored DT data, claimed longer ago than this and not being worked on by this
         * replica, is picked up again by the sweep (architecture.md 3.2 reconcile).
         */
        @WithDefault("PT5M")
        Duration fetchTimeout();
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

        /** How long one batch may wait for broker acknowledgements before the round is retried. */
        @WithDefault("PT30S")
        Duration sendTimeout();

        /** Published rows older than this are deleted. */
        @WithDefault("P1D")
        Duration retention();
    }

    interface Leader {
        /** Timeout of the lock connection's validity check (D-035). */
        @WithDefault("PT2S")
        Duration checkTimeout();

        /** Postgres advisory lock key held by the active controller (D-006). */
        @WithDefault("7304110001")
        long lockKey();
    }

    interface Schema {
        /** Fail start-up while the Liquibase changelog has changesets reachability-api has not applied (D-031). */
        @WithDefault("true")
        boolean checkAtStart();
    }

    interface S3 {
        String bucket();

        String region();

        /** For an S3-compatible mock (D-034); unset in production. */
        Optional<URI> endpointOverride();

        @WithDefault("false")
        boolean pathStyle();

        /** Presigned URLs for Jobs live for the Job's deadline plus this margin (architecture.md 9). */
        @WithDefault("PT15M")
        Duration jobLinkMargin();
    }

    interface Github {
        /** REST API base; https://<host>/api/v3 on GitHub Enterprise Server. */
        @WithDefault("https://api.github.com")
        URI apiUrl();

        /** Required when Jobs are enabled. The App's private key is {@code reach.github.private-key}, from Vault. */
        Optional<String> appId();
    }

    interface Job {
        /** False stops after the DT data is loaded (local runs without a cluster). */
        @WithDefault("true")
        boolean enabled();

        /** {@code ttlSecondsAfterFinished} of every Job. */
        @WithDefault("PT10M")
        Duration ttlAfterFinished();

        /** Informer resync; each resync re-checks every Job the controller owns. */
        @WithDefault("PT5M")
        Duration resync();

        Stage fetch();

        Stage scan();
    }

    interface Stage {
        String image();

        String namespace();

        String serviceAccount();

        Duration activeDeadline();

        String cpu();

        String memory();

        String ephemeralStorage();

        /** emptyDir size for /work. */
        String workSize();

        /** Node selector for the pod, e.g. the scanner MachineSet label (D-009). */
        Map<String, String> nodeSelector();

        /** Taint the pod tolerates (NoSchedule), e.g. the scanner MachineSet taint. */
        Optional<String> tolerationKey();

        /** {@code hostUsers: false} (user namespaces) on OCP 4.20+ with restricted-v3 (D-009). */
        @WithDefault("true")
        boolean hostUsers();
    }
}
