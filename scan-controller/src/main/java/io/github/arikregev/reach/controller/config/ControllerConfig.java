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

    Artifactory artifactory();

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

        /** How a scan's repository must match the DT project's VCS reference or {@code reach-repo=} tag (D-037). */
        @WithDefault("WRITEBACK")
        RepoBindingMode repoBinding();
    }

    /**
     * {@code REQUIRED}: an unbound project fails the scan with {@code DT_REPO_UNBOUND}. {@code WRITEBACK}: an unbound
     * project is scanned, with DT write-back capped at COMMENT. {@code OFF}: no check. A project bound to another
     * repository fails with {@code DT_REPO_MISMATCH} unless the mode is OFF.
     */
    enum RepoBindingMode {
        REQUIRED,
        WRITEBACK,
        OFF
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

    interface Artifactory {
        /**
         * JFrog platform URL, e.g. {@code https://artifactory.example}: the Access API is under it and the
         * repositories under {@code /artifactory}. Unset: scan Jobs get no token and build
         * against public registries, which only the local stack does (D-011, D-044). The admin token that mints the
         * per-scan tokens is {@code reach.artifactory.admin-token}, from Vault.
         */
        Optional<URI> url();

        /** Token lifetime as a multiple of the scan Job's {@code activeDeadlineSeconds} (D-011). */
        @WithDefault("2.0")
        double tokenTtlFactor();

        /** Scope of the per-scan token: a group with read access to the virtual repositories only. */
        @WithDefault("applied-permissions/groups:reach-scan")
        String tokenScope();
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
