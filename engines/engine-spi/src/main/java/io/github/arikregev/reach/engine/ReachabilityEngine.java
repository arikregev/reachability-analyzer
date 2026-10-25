package io.github.arikregev.reach.engine;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * One reachability engine (architecture.md 7.2). The scan runner finds engines through CDI, asks each in
 * {@link #rank()} order whether it {@link #supports} a module, builds the module with {@link #prepare} and analyses
 * it with {@link #analyse}. The next rank is the fallback when an engine fails.
 */
public interface ReachabilityEngine {

    /** "sootup", "pycg", "govulncheck", "jelly", ... */
    String id();

    String version();

    Set<Ecosystem> ecosystems();

    /** Lower runs first. */
    int rank();

    boolean supports(ModuleDescriptor module);

    PreparedModule prepare(ModuleDescriptor module, BuildEnv env) throws BuildException;

    /**
     * Verdicts for the targets whose component this module resolves. Targets the module does not contain are left
     * out, so the runner can tell COMPONENT_NOT_RESOLVED apart. Must return within {@code budget}.
     */
    EngineResult analyse(PreparedModule module, List<TargetFinding> targets, Duration budget);
}
