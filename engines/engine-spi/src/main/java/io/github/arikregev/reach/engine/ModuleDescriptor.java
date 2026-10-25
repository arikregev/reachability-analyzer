package io.github.arikregev.reach.engine;

import java.nio.file.Path;

/**
 * A build root found in the source tree: {@code dir} holds the build file, {@code path} is that directory relative
 * to the repository root ("" for the root). A Maven reactor or a Gradle build is one descriptor, however many
 * subprojects it has.
 */
public record ModuleDescriptor(Path dir, String path, Ecosystem ecosystem, String buildTool) {
}
