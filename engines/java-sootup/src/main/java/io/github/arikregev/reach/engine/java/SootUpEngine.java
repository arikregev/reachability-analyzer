package io.github.arikregev.reach.engine.java;

import io.github.arikregev.reach.engine.BuildEnv;
import io.github.arikregev.reach.engine.BuildException;
import io.github.arikregev.reach.engine.CallPath;
import io.github.arikregev.reach.engine.Ecosystem;
import io.github.arikregev.reach.engine.EngineResult;
import io.github.arikregev.reach.engine.FindingReason;
import io.github.arikregev.reach.engine.FindingVerdict;
import io.github.arikregev.reach.engine.Level;
import io.github.arikregev.reach.engine.ModuleDescriptor;
import io.github.arikregev.reach.engine.PreparedModule;
import io.github.arikregev.reach.engine.ReachabilityEngine;
import io.github.arikregev.reach.engine.TargetFinding;
import io.github.arikregev.reach.engine.Verdict;
import io.github.arikregev.reach.engine.VulnerableSymbol;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.jboss.logging.Logger;
import sootup.core.signatures.MethodSignature;

/**
 * Java (Maven and Gradle) with SootUp class hierarchy analysis (architecture.md 7.1, D-043). Verdict rules per
 * finding, for each subproject whose runtime classpath contains the finding's jar:
 * <ul>
 * <li>a vulnerable method from the knowledge base is reached: REACHABLE, level SYMBOL;</li>
 * <li>only class or package symbols, and one of them is reached: POTENTIALLY_REACHABLE, level CLASS_OR_PACKAGE;</li>
 * <li>no symbol data, and any code of the jar is reached: POTENTIALLY_REACHABLE / NO_SYMBOL_DATA;</li>
 * <li>nothing relevant reached: NOT_REACHABLE, at the level of the data it was checked against.</li>
 * </ul>
 * Subprojects are merged by the strongest verdict. Running out of budget gives UNKNOWN / ENGINE_TIMEOUT for the
 * findings not yet decided.
 */
@ApplicationScoped
public class SootUpEngine implements ReachabilityEngine {

    private static final Logger LOG = Logger.getLogger(SootUpEngine.class);
    static final String VERSION = "3.0.1";
    private static final int MAX_PATHS = 3;

    @Override
    public String id() {
        return "sootup";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<Ecosystem> ecosystems() {
        return Set.of(Ecosystem.MAVEN);
    }

    @Override
    public int rank() {
        return 10;
    }

    @Override
    public boolean supports(ModuleDescriptor module) {
        return module.ecosystem() == Ecosystem.MAVEN
                && (module.buildTool().equals(JavaBuild.MAVEN) || module.buildTool().equals(JavaBuild.GRADLE));
    }

    @Override
    public PreparedModule prepare(ModuleDescriptor module, BuildEnv env) throws BuildException {
        return JavaBuild.prepare(module, env);
    }

    @Override
    public EngineResult analyse(PreparedModule module, List<TargetFinding> targets, Duration budget) {
        Instant deadline = Instant.now().plus(budget);
        Map<TargetFinding, FindingVerdict> merged = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        boolean complete = true;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (PreparedModule.Unit unit : module.units()) {
                Map<TargetFinding, List<Path>> resolved = resolve(unit, targets);
                if (resolved.isEmpty()) {
                    continue;
                }
                Duration left = Duration.between(Instant.now(), deadline);
                Future<Map<TargetFinding, FindingVerdict>> f = executor.submit(() -> analyseUnit(unit, resolved, diagnostics));
                Map<TargetFinding, FindingVerdict> verdicts;
                try {
                    verdicts = f.get(Math.max(left.toMillis(), 1), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    complete = false;
                    diagnostics.add(unit.name() + ": analysis stopped at the time budget");
                    verdicts = new LinkedHashMap<>();
                    for (TargetFinding t : resolved.keySet()) {
                        verdicts.put(t, FindingVerdict.unknown(t, FindingReason.ENGINE_TIMEOUT));
                    }
                } catch (ExecutionException e) {
                    LOG.warnf(e.getCause(), "Analysis of %s failed", unit.name());
                    diagnostics.add(unit.name() + ": analysis failed: " + e.getCause());
                    verdicts = new LinkedHashMap<>();
                    for (TargetFinding t : resolved.keySet()) {
                        verdicts.put(t, FindingVerdict.unknown(t, FindingReason.ENGINE_FAILED));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    f.cancel(true);
                    return new EngineResult(List.copyOf(merged.values()), false, diagnostics);
                }
                verdicts.forEach((t, v) -> merged.merge(t, v, SootUpEngine::stronger));
            }
        } finally {
            executor.shutdownNow();
        }
        return new EngineResult(List.copyOf(merged.values()), complete, diagnostics);
    }

    /** The jars of the unit's classpath that are each target's component. */
    private static Map<TargetFinding, List<Path>> resolve(PreparedModule.Unit unit, List<TargetFinding> targets) {
        Map<TargetFinding, List<Path>> out = new LinkedHashMap<>();
        for (TargetFinding t : targets) {
            Optional<MavenPurl> purl = MavenPurl.parse(t.purl());
            if (purl.isEmpty()) {
                continue;
            }
            List<Path> jars = unit.dependencies().stream().filter(purl.get()::matches).toList();
            if (!jars.isEmpty()) {
                out.put(t, jars);
            }
        }
        return out;
    }

    private Map<TargetFinding, FindingVerdict> analyseUnit(PreparedModule.Unit unit, Map<TargetFinding, List<Path>> resolved,
                                                           List<String> diagnostics) {
        long start = System.nanoTime();
        CallGraphSearch search = CallGraphSearch.build(unit.appRoots(), unit.dependencies());
        synchronized (diagnostics) {
            diagnostics.add("%s: %d entry points, %d methods reached, %d ms".formatted(unit.name(),
                    search.entryPointCount(), search.reachedCount(), (System.nanoTime() - start) / 1_000_000));
        }
        Map<TargetFinding, FindingVerdict> out = new LinkedHashMap<>();
        for (Map.Entry<TargetFinding, List<Path>> e : resolved.entrySet()) {
            out.put(e.getKey(), verdict(search, e.getKey(), e.getValue()));
        }
        return out;
    }

    static FindingVerdict verdict(CallGraphSearch search, TargetFinding t, List<Path> jars) {
        Set<String> jarClasses = new HashSet<>();
        jars.forEach(j -> jarClasses.addAll(CallGraphSearch.classNames(j)));
        Predicate<MethodSignature> inJar = m -> jarClasses.contains(m.getDeclClassType().getFullyQualifiedName());

        List<VulnerableSymbol> methods = t.symbols().stream()
                .filter(s -> s.kind() == VulnerableSymbol.Kind.METHOD || s.kind() == VulnerableSymbol.Kind.FUNCTION).toList();
        List<VulnerableSymbol> coarse = t.symbols().stream()
                .filter(s -> s.kind() == VulnerableSymbol.Kind.CLASS || s.kind() == VulnerableSymbol.Kind.PACKAGE).toList();

        if (!methods.isEmpty()) {
            List<MethodSignature> hits = search.reachedIn(m -> inJar.test(m) && methods.stream().anyMatch(s -> matchesMethod(s, m)));
            if (!hits.isEmpty()) {
                return new FindingVerdict(t, Verdict.REACHABLE, FindingReason.SYMBOL_REACHED, Level.SYMBOL, paths(search, hits));
            }
        }
        if (!coarse.isEmpty()) {
            List<MethodSignature> hits = search.reachedIn(m -> inJar.test(m) && coarse.stream().anyMatch(s -> matchesType(s, m)));
            if (!hits.isEmpty()) {
                return new FindingVerdict(t, Verdict.POTENTIALLY_REACHABLE, FindingReason.SYMBOL_REACHED,
                        Level.CLASS_OR_PACKAGE, paths(search, hits));
            }
        }
        if (!methods.isEmpty()) {
            return new FindingVerdict(t, Verdict.NOT_REACHABLE, FindingReason.SYMBOL_NOT_REACHED, Level.SYMBOL, List.of());
        }
        if (!coarse.isEmpty()) {
            return new FindingVerdict(t, Verdict.NOT_REACHABLE, FindingReason.SYMBOL_NOT_REACHED, Level.CLASS_OR_PACKAGE, List.of());
        }
        List<MethodSignature> any = search.reachedIn(inJar);
        if (!any.isEmpty()) {
            return new FindingVerdict(t, Verdict.POTENTIALLY_REACHABLE, FindingReason.NO_SYMBOL_DATA,
                    Level.CLASS_OR_PACKAGE, paths(search, any));
        }
        return new FindingVerdict(t, Verdict.NOT_REACHABLE, FindingReason.COMPONENT_NOT_REFERENCED, Level.CLASS_OR_PACKAGE, List.of());
    }

    /** {@code com.acme.Parser.parse} matches every overload of {@code parse} declared in {@code com.acme.Parser}. */
    static boolean matchesMethod(VulnerableSymbol s, MethodSignature m) {
        String symbol = s.symbol();
        int paren = symbol.indexOf('(');
        if (paren >= 0) {
            symbol = symbol.substring(0, paren);
        }
        int dot = symbol.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String cls = symbol.substring(0, dot);
        String name = symbol.substring(dot + 1);
        String declared = m.getDeclClassType().getFullyQualifiedName();
        return m.getName().equals(name) && (declared.equals(cls) || declared.replace('$', '.').equals(cls));
    }

    static boolean matchesType(VulnerableSymbol s, MethodSignature m) {
        String declared = m.getDeclClassType().getFullyQualifiedName();
        if (s.kind() == VulnerableSymbol.Kind.PACKAGE) {
            return declared.startsWith(s.symbol() + ".");
        }
        return declared.equals(s.symbol()) || declared.replace('$', '.').equals(s.symbol())
                || declared.startsWith(s.symbol() + "$");
    }

    private static List<CallPath> paths(CallGraphSearch search, List<MethodSignature> hits) {
        return hits.stream().limit(MAX_PATHS).map(search::pathTo).toList();
    }

    static FindingVerdict stronger(FindingVerdict a, FindingVerdict b) {
        if (b.verdict().ordinal() != a.verdict().ordinal()) {
            return b.verdict().ordinal() > a.verdict().ordinal() ? b : a;
        }
        return b.level().ordinal() < a.level().ordinal() ? b : a;
    }
}
