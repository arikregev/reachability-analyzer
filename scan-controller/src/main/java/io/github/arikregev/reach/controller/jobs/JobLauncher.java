package io.github.arikregev.reach.controller.jobs;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.controller.artifactory.ArtifactoryTokens;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.s3.S3Storage;
import io.github.arikregev.reach.controller.s3.ScanKeys;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Creates the per-scan Jobs (architecture.md 3.3, 3.4). Order: the Job, then its Secret with the Job already as
 * owner, so the Secret can never outlive the Job; the pod waits in ContainerCreating until the Secret exists. If the
 * Secret cannot be created, the Job is deleted again. Creating an existing Job is a no-op, so a new leader can
 * relaunch safely.
 */
@ApplicationScoped
public class JobLauncher {

    private static final Logger LOG = Logger.getLogger(JobLauncher.class);

    /** A single presigned PUT is limited to 5 GB (3.3). */
    public static final long MAX_TARBALL_BYTES = 5L * 1024 * 1024 * 1024;

    /** Kept from the scan Job's deadline for image pull and start-up; the scanner budgets the rest (D-042). */
    static final Duration SCANNER_STARTUP = Duration.ofMinutes(3);

    static final String ARTIFACTORY_TOKEN = "artifactory-token";
    static final String ARTIFACTORY_TOKEN_ID = "artifactory-token-id";

    private final KubernetesClient client;
    private final S3Storage s3;
    private final ControllerConfig config;
    private final MeterRegistry registry;
    private final ArtifactoryTokens artifactory;

    public JobLauncher(KubernetesClient client, S3Storage s3, ControllerConfig config, MeterRegistry registry,
                       ArtifactoryTokens artifactory) {
        this.client = client;
        this.s3 = s3;
        this.config = config;
        this.registry = registry;
        this.artifactory = artifactory;
    }

    /** source-fetch: clone with the installation token, PUT the tarball to a presigned URL. */
    public void launchFetch(ClaimedScan scan, String githubToken) {
        ControllerConfig.Stage c = config.job().fetch();
        ScanKeys keys = keys(scan);
        Map<String, String> files = Map.of(
                "github-token", githubToken,
                "source-put-url", s3.presignPut(keys.sourceTarball(), linkTtl(c)).toString());
        List<EnvVar> env = List.of(
                env("REPO_URL", scan.repoUrl()),
                env("GIT_HOST", URI.create(scan.repoUrl()).getHost()),
                env("COMMIT_SHA", scan.commitSha()),
                env("MAX_TARBALL_BYTES", Long.toString(MAX_TARBALL_BYTES)));
        launch(Stage.FETCH, scan, c, files, env);
    }

    /**
     * scan: GET the tarball and input bundle, PUT summary.json, resolve dependencies through Artifactory with a token
     * minted for this attempt (D-044). The token's id goes into the Secret too, so the watcher can revoke it when the
     * Job ends; if the launch fails, it is revoked here.
     */
    public void launchScan(ClaimedScan scan) {
        ControllerConfig.Stage c = config.job().scan();
        ScanKeys keys = keys(scan);
        Duration ttl = linkTtl(c);
        Map<String, String> files = new HashMap<>(Map.of(
                "source-get-url", s3.presignGet(keys.sourceTarball(), ttl).toString(),
                "input-get-url", s3.presignGet(keys.inputBundle(), ttl).toString(),
                "summary-put-url", s3.presignPut(keys.summary(), ttl).toString()));
        Duration deadline = c.activeDeadline().minus(SCANNER_STARTUP);
        List<EnvVar> env = new ArrayList<>(List.of(
                env("SCAN_ID", scan.id().toString()),
                env("COMMIT_SHA", scan.commitSha()),
                env("REACH_SCANNER_DEADLINE", (deadline.isNegative() ? c.activeDeadline().dividedBy(2) : deadline).toString()),
                env("REACH_SCANNER_IMAGE", c.image())));
        Optional<ArtifactoryTokens.Token> token = Optional.empty();
        if (artifactory.enabled()) {
            token = Optional.of(artifactory.mint(scan.id(), scan.attempt(), c.activeDeadline()));
            files.put(ARTIFACTORY_TOKEN, token.get().value());
            files.put(ARTIFACTORY_TOKEN_ID, token.get().id());
            env.add(env("REACH_SCANNER_ARTIFACTORY_URL", ArtifactoryTokens.repositoriesUrl(config.artifactory().url().orElseThrow())));
        }
        try {
            launch(Stage.SCAN, scan, c, files, env);
        } catch (JobLaunchException e) {
            token.ifPresent(t -> artifactory.revoke(t.id()));
            throw e;
        }
    }

    /** Whether the stage's Job for the scan's current attempt exists. */
    public boolean exists(Stage stage, ClaimedScan scan) {
        String ns = namespace(stage);
        try {
            return client.batch().v1().jobs().inNamespace(ns)
                    .withName(JobSpecs.name(stage, scan.id(), scan.attempt())).get() != null;
        } catch (KubernetesClientException e) {
            throw new JobLaunchException("reading the " + stage.label() + " Job failed: " + e.getMessage(), e);
        }
    }

    /** Deletes a Job and its pods (cancel, stale attempt). */
    public void delete(Stage stage, ClaimedScan scan, int attempt) {
        String ns = namespace(stage);
        try {
            client.batch().v1().jobs().inNamespace(ns).withName(JobSpecs.name(stage, scan.id(), attempt))
                    .withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        } catch (KubernetesClientException e) {
            LOG.warnf("Scan %s: deleting the %s Job failed: %s", scan.id(), stage.label(), e.getMessage());
        }
    }

    public static ScanKeys keys(ClaimedScan scan) {
        return new ScanKeys(scan.org(), scan.repo(), scan.commitSha(), scan.id(), scan.attempt());
    }

    private Duration linkTtl(ControllerConfig.Stage c) {
        return c.activeDeadline().plus(config.s3().jobLinkMargin());
    }

    private void launch(Stage stage, ClaimedScan scan, ControllerConfig.Stage c, Map<String, String> files,
                        List<EnvVar> env) {
        String ns = c.namespace();
        Job job = JobSpecs.job(stage, scan, config.job(), c, env);
        Job live;
        try {
            try {
                live = client.batch().v1().jobs().inNamespace(ns).resource(job).create();
                registry.counter("reach.jobs.created", "stage", stage.label()).increment();
                LOG.infof("Scan %s: created Job %s/%s", scan.id(), ns, job.getMetadata().getName());
            } catch (KubernetesClientException e) {
                if (e.getCode() != 409) {
                    throw e;
                }
                live = client.batch().v1().jobs().inNamespace(ns).withName(job.getMetadata().getName()).get();
            }
        } catch (KubernetesClientException e) {
            throw new JobLaunchException("creating the " + stage.label() + " Job failed: " + e.getMessage(), e);
        }
        if (live == null) {
            throw new JobLaunchException("the " + stage.label() + " Job disappeared right after creation", null);
        }
        Secret secret = JobSpecs.secret(stage, scan, ns, files);
        secret.getMetadata().setOwnerReferences(List.of(ownerRef(live)));
        try {
            putSecret(secret);
        } catch (KubernetesClientException e) {
            delete(stage, scan, scan.attempt());
            throw new JobLaunchException("creating the " + stage.label() + " Secret failed: " + e.getMessage(), e);
        }
    }

    /** The fetch Job's installation token, read back from its Secret, or empty once the Secret is gone. */
    public Optional<String> fetchToken(ClaimedScan scan, int attempt) {
        return secretFile(Stage.FETCH, scan, attempt, "github-token");
    }

    /** The scan Job's Artifactory token id, read back from its Secret, or empty once the Secret is gone. */
    public Optional<String> scanTokenId(ClaimedScan scan, int attempt) {
        return secretFile(Stage.SCAN, scan, attempt, ARTIFACTORY_TOKEN_ID);
    }

    private Optional<String> secretFile(Stage stage, ClaimedScan scan, int attempt, String file) {
        Secret s = client.secrets().inNamespace(namespace(stage)).withName(JobSpecs.name(stage, scan.id(), attempt)).get();
        if (s == null || s.getData() == null || !s.getData().containsKey(file)) {
            return Optional.empty();
        }
        return Optional.of(new String(Base64.getDecoder().decode(s.getData().get(file)), StandardCharsets.UTF_8));
    }

    private String namespace(Stage stage) {
        return stage == Stage.FETCH ? config.job().fetch().namespace() : config.job().scan().namespace();
    }

    /** Deletes the stage's Secret; the Job stays until its TTL. */
    public void deleteSecret(Stage stage, ClaimedScan scan, int attempt) {
        String ns = namespace(stage);
        client.secrets().inNamespace(ns).withName(JobSpecs.name(stage, scan.id(), attempt)).delete();
    }

    /** Secrets are immutable, so a relaunch replaces the old one (its token or links may have expired). */
    private void putSecret(Secret secret) {
        var secrets = client.secrets().inNamespace(secret.getMetadata().getNamespace());
        try {
            secrets.resource(secret).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) {
                throw e;
            }
            secrets.withName(secret.getMetadata().getName()).delete();
            secrets.resource(secret).create();
        }
    }

    private static OwnerReference ownerRef(Job job) {
        return new OwnerReferenceBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withName(job.getMetadata().getName())
                .withUid(job.getMetadata().getUid())
                .withBlockOwnerDeletion(false)
                .build();
    }

    private static EnvVar env(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    /** {@code JOB_LAUNCH_FAILED}: the Kubernetes API refused or failed (quota, outage). Retryable. */
    public static class JobLaunchException extends RuntimeException {

        public JobLaunchException(String message, Throwable cause) {
            super(message, cause);
        }

        public ReasonCode reason() {
            return ReasonCode.JOB_LAUNCH_FAILED;
        }
    }
}
