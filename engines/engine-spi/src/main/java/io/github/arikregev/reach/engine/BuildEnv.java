package io.github.arikregev.reach.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Where a build may write and how it reaches dependencies (D-011). {@code artifactoryUrl} is the base URL of the
 * Artifactory instance; each ecosystem appends its repository. {@code environment} is added to every build
 * process. Absent Artifactory settings mean builds use the tools' defaults (tests and local runs only).
 */
public record BuildEnv(Path workDir, Path home, Optional<String> artifactoryUrl, Optional<String> artifactoryToken,
                       Map<String, String> repositories, Map<String, String> environment, Duration buildTimeout) {

    public BuildEnv {
        repositories = Map.copyOf(repositories);
        environment = Map.copyOf(environment);
    }

    public BuildEnv withTimeout(Duration timeout) {
        return new BuildEnv(workDir, home, artifactoryUrl, artifactoryToken, repositories, environment, timeout);
    }

    /** The URL of an ecosystem's Artifactory repository, e.g. {@code <base>/maven-virtual}. */
    public Optional<String> repositoryUrl(String key) {
        return artifactoryUrl.flatMap(base -> Optional.ofNullable(repositories.get(key))
                .map(repo -> base.replaceAll("/+$", "") + "/" + repo.replaceAll("^/+", "")));
    }
}
