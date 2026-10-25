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
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.s3.S3Storage;
import io.github.arikregev.reach.controller.s3.ScanKeys;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Creates the per-scan Jobs (architecture.md 3.3, 3.4). Order: Secret, Job, then the Secret gets the Job as owner so
 * both go when the Job's TTL expires. Creating an existing Job is a no-op, so a new leader can relaunch safely.
 */
@ApplicationScoped
public class JobLauncher {

    private static final Logger LOG = Logger.getLogger(JobLauncher.class);

    /** A single presigned PUT is limited to 5 GB (3.3). */
    public static final long MAX_TARBALL_BYTES = 5L * 1024 * 1024 * 1024;

    private final KubernetesClient client;
    private final S3Storage s3;
    private final ControllerConfig config;
    private final MeterRegistry registry;

    public JobLauncher(KubernetesClient client, S3Storage s3, ControllerConfig config, MeterRegistry registry) {
        this.client = client;
        this.s3 = s3;
        this.config = config;
        this.registry = registry;
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

    /** scan: GET the tarball and input bundle, PUT summary.json. The Artifactory token is added with the scanner. */
    public void launchScan(ClaimedScan scan) {
        ControllerConfig.Stage c = config.job().scan();
        ScanKeys keys = keys(scan);
        Duration ttl = linkTtl(c);
        Map<String, String> files = Map.of(
                "source-get-url", s3.presignGet(keys.sourceTarball(), ttl).toString(),
                "input-get-url", s3.presignGet(keys.inputBundle(), ttl).toString(),
                "summary-put-url", s3.presignPut(keys.summary(), ttl).toString());
        List<EnvVar> env = List.of(
                env("SCAN_ID", scan.id().toString()),
                env("COMMIT_SHA", scan.commitSha()));
        launch(Stage.SCAN, scan, c, files, env);
    }

    /** Whether the stage's Job for the scan's current attempt exists. */
    public boolean exists(Stage stage, ClaimedScan scan) {
        String ns = stage == Stage.FETCH ? config.job().fetch().namespace() : config.job().scan().namespace();
        try {
            return client.batch().v1().jobs().inNamespace(ns)
                    .withName(JobSpecs.name(stage, scan.id(), scan.attempt())).get() != null;
        } catch (KubernetesClientException e) {
            throw new JobLaunchException("reading the " + stage.label() + " Job failed: " + e.getMessage(), e);
        }
    }

    /** Deletes a Job and its pods (cancel, stale attempt). */
    public void delete(Stage stage, ClaimedScan scan, int attempt) {
        String ns = stage == Stage.FETCH ? config.job().fetch().namespace() : config.job().scan().namespace();
        try {
            client.batch().v1().jobs().inNamespace(ns).withName(JobSpecs.name(stage, scan.id(), attempt))
                    .withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        } catch (KubernetesClientException e) {
            LOG.warnf("Scan %s: deleting the %s Job failed: %s", scan.id(), stage.label(), e.getMessage());
        }
    }

    public static ScanKeys keys(ClaimedScan scan) {
        return new ScanKeys(scan.org(), scan.repo(), scan.commitSha(), scan.id());
    }

    private Duration linkTtl(ControllerConfig.Stage c) {
        return c.activeDeadline().plus(config.s3().jobLinkMargin());
    }

    private void launch(Stage stage, ClaimedScan scan, ControllerConfig.Stage c, Map<String, String> files,
                        List<EnvVar> env) {
        String ns = c.namespace();
        Secret secret = JobSpecs.secret(stage, scan, ns, files);
        Job job = JobSpecs.job(stage, scan, config.job(), c, env);
        try {
            putSecret(secret);
            Job live;
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
            if (live != null) {
                own(secret, live);
            }
        } catch (KubernetesClientException e) {
            throw new JobLaunchException("creating the " + stage.label() + " Job failed: " + e.getMessage(), e);
        }
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

    private void own(Secret secret, Job job) {
        OwnerReference ref = new OwnerReferenceBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withName(job.getMetadata().getName())
                .withUid(job.getMetadata().getUid())
                .withBlockOwnerDeletion(false)
                .build();
        client.secrets().inNamespace(secret.getMetadata().getNamespace()).withName(secret.getMetadata().getName())
                .edit(s -> {
                    s.getMetadata().setOwnerReferences(List.of(ref));
                    return s;
                });
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
