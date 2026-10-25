package io.github.arikregev.reach.scanner;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Set by the controller through the Job's environment (architecture.md 3.4, 12). */
@ConfigMapping(prefix = "reach.scanner")
public interface ScannerConfig {

    /** The per-Job Secret's files: source-get-url, input-get-url, summary-put-url, artifactory-token. */
    @WithDefault("/var/run/reach")
    Path secretsDir();

    /** The emptyDir the source, the builds and the tools' caches go to. */
    @WithDefault("/work")
    Path workDir();

    /** The scan id the controller expects; the input bundle must match. */
    Optional<String> scanId();

    /** The Job's activeDeadlineSeconds. The runner stops starting work before it runs out. */
    @WithDefault("PT45M")
    Duration deadline();

    /** Time kept back from the deadline for the summary upload. */
    @WithDefault("PT2M")
    Duration reserve();

    /** Image reference, recorded in the summary. */
    Optional<String> image();

    Artifactory artifactory();

    interface Artifactory {
        /** Base URL, e.g. {@code https://artifactory.example/artifactory}. Unset: builds use public registries (local runs only). */
        Optional<String> url();

        /** Repository per build tool, appended to the base URL (D-011); defaults in application.properties. */
        Map<String, String> repositories();
    }
}
