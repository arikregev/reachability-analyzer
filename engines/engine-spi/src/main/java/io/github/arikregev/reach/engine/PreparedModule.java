package io.github.arikregev.reach.engine;

import java.util.List;

/**
 * A built module ready for analysis. {@code units} are what the engine analyses separately (one per Maven or
 * Gradle subproject for Java); {@code diagnostics} are build notes kept for the summary.
 */
public record PreparedModule(ModuleDescriptor module, String toolchain, List<Unit> units, List<String> diagnostics) {

    public PreparedModule {
        units = List.copyOf(units);
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * One analysable unit: its own code ({@code appRoots}) and its resolved runtime dependencies
     * ({@code dependencies}), both as file system paths.
     */
    public record Unit(String name, List<java.nio.file.Path> appRoots, List<java.nio.file.Path> dependencies) {

        public Unit {
            appRoots = List.copyOf(appRoots);
            dependencies = List.copyOf(dependencies);
        }
    }
}
