package io.github.arikregev.reach.controller.jobs;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job and Secret manifests for both stages (architecture.md 9): {@code backoffLimit: 0}, {@code restartPolicy: Never},
 * a deadline, a TTL, a podFailurePolicy that marks node disruptions, no service account token, read-only root FS,
 * all capabilities dropped, emptyDir for every writable path, and the stage's credentials as files from a per-Job
 * Secret.
 */
public final class JobSpecs {

    public static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String MANAGER = "scan-controller";
    public static final String SCAN_ID = "reach/scan-id";
    public static final String STAGE = "reach/stage";
    public static final String ATTEMPT = "reach/attempt";
    /** Set by the Job controller on its pods. */
    public static final String JOB_NAME = "batch.kubernetes.io/job-name";
    public static final String SECRETS_DIR = "/var/run/reach";

    private JobSpecs() {
    }

    /** {@code reach-fetch-<scanId>-<attempt>}: at most 51 characters, under the 63 of the job-name label. */
    public static String name(Stage stage, UUID scanId, int attempt) {
        return "reach-" + stage.label() + "-" + scanId + "-" + attempt;
    }

    public static Map<String, String> labels(Stage stage, ClaimedScan scan) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_BY, MANAGER);
        labels.put("app.kubernetes.io/part-of", "reachability-analyzer");
        labels.put(SCAN_ID, scan.id().toString());
        labels.put(STAGE, stage.label());
        labels.put(ATTEMPT, Integer.toString(scan.attempt()));
        return labels;
    }

    public static Secret secret(Stage stage, ClaimedScan scan, String namespace, Map<String, String> files) {
        Map<String, String> data = new LinkedHashMap<>();
        Base64.Encoder b64 = Base64.getEncoder();
        files.forEach((k, v) -> data.put(k, b64.encodeToString(v.getBytes(StandardCharsets.UTF_8))));
        return new SecretBuilder()
                .withNewMetadata()
                    .withName(name(stage, scan.id(), scan.attempt()))
                    .withNamespace(namespace)
                    .withLabels(labels(stage, scan))
                .endMetadata()
                .withType("Opaque")
                .withData(data)
                .withImmutable(true)
                .build();
    }

    public static Job job(Stage stage, ClaimedScan scan, ControllerConfig.Job jobs, ControllerConfig.Stage c,
                          List<EnvVar> env) {
        String name = name(stage, scan.id(), scan.attempt());
        Map<String, String> labels = labels(stage, scan);
        Map<String, Quantity> resources = Map.of(
                "cpu", new Quantity(c.cpu()),
                "memory", new Quantity(c.memory()),
                "ephemeral-storage", new Quantity(c.ephemeralStorage()));
        Container container = new ContainerBuilder()
                .withName(stage.label())
                .withImage(c.image())
                .withImagePullPolicy("IfNotPresent")
                .withEnv(env)
                .addNewEnv().withName("HOME").withValue("/home/reach").endEnv()
                .addNewEnv().withName("REACH_SECRETS_DIR").withValue(SECRETS_DIR).endEnv()
                .withNewResources().withRequests(resources).withLimits(resources).endResources()
                .withNewSecurityContext()
                    .withAllowPrivilegeEscalation(false)
                    .withReadOnlyRootFilesystem(true)
                    .withRunAsNonRoot(true)
                    .withNewCapabilities().withDrop("ALL").endCapabilities()
                .endSecurityContext()
                .addNewVolumeMount().withName("secrets").withMountPath(SECRETS_DIR).withReadOnly(true).endVolumeMount()
                .addNewVolumeMount().withName("work").withMountPath("/work").endVolumeMount()
                .addNewVolumeMount().withName("tmp").withMountPath("/tmp").endVolumeMount()
                .addNewVolumeMount().withName("home").withMountPath("/home/reach").endVolumeMount()
                .build();
        PodSpecBuilder pod = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withServiceAccountName(c.serviceAccount())
                .withAutomountServiceAccountToken(false)
                .withEnableServiceLinks(false)
                .withNewSecurityContext()
                    .withRunAsNonRoot(true)
                    .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                .endSecurityContext()
                .withNodeSelector(c.nodeSelector().isEmpty() ? null : c.nodeSelector())
                .withTolerations(c.tolerationKey().map(JobSpecs::toleration).stream().toList())
                .withContainers(container)
                .withVolumes(
                        new VolumeBuilder().withName("secrets")
                                .withNewSecret().withSecretName(name).withDefaultMode(0440).endSecret().build(),
                        emptyDir("work", c.workSize()),
                        emptyDir("tmp", "1Gi"),
                        emptyDir("home", "1Gi"));
        if (!c.hostUsers()) {
            pod.withHostUsers(false);
        }
        PodSpec spec = pod.build();
        return new JobBuilder()
                .withNewMetadata().withName(name).withNamespace(c.namespace()).withLabels(labels).endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withActiveDeadlineSeconds(c.activeDeadline().toSeconds())
                    .withTtlSecondsAfterFinished((int) jobs.ttlAfterFinished().toSeconds())
                    // A pod lost to eviction or node failure fails the Job with reason PodFailurePolicy, which the
                    // watcher retries as POD_DISRUPTED instead of failing the scan (E-11, D-036).
                    .withNewPodFailurePolicy()
                        .addNewRule()
                            .withAction("FailJob")
                            .addNewOnPodCondition().withType("DisruptionTarget").withStatus("True").endOnPodCondition()
                        .endRule()
                    .endPodFailurePolicy()
                    .withNewTemplate()
                        .withNewMetadata().withLabels(labels).endMetadata()
                        .withSpec(spec)
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static Volume emptyDir(String name, String size) {
        return new VolumeBuilder().withName(name).withNewEmptyDir().withSizeLimit(new Quantity(size)).endEmptyDir().build();
    }

    private static Toleration toleration(String key) {
        return new TolerationBuilder().withKey(key).withOperator("Exists").withEffect("NoSchedule").build();
    }
}
