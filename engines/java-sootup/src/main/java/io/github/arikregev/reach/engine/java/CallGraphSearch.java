package io.github.arikregev.reach.engine.java;

import io.github.arikregev.reach.engine.CallPath;
import io.github.arikregev.reach.engine.Frame;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.common.constant.MethodHandle;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JDynamicInvokeExpr;
import sootup.core.jimple.common.stmt.InvokableStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

/**
 * The call graph of one unit (a Maven or Gradle subproject) and searches over it (architecture.md 7.1). Built with
 * SootUp's class hierarchy analysis from the day-1 entry points: every {@code main} method and every public method
 * of an application class. CHA does not follow {@code invokedynamic}, so lambda and method-reference bodies are
 * added as edges from the method that creates them, and the graph is rebuilt until no new lambda target appears.
 * JDK classes are not loaded: calls into the JDK end there, and callbacks from the JDK into application code are
 * covered by treating public application methods as entry points.
 */
final class CallGraphSearch {

    private static final String LAMBDA_METAFACTORY = "java.lang.invoke.LambdaMetafactory";
    private static final int MAX_ROUNDS = 10;

    private final JavaView view;
    private final Set<MethodSignature> entryPoints;
    private final Map<MethodSignature, List<Edge>> edges = new HashMap<>();
    private final Map<MethodSignature, Edge> parent = new HashMap<>();
    private final Set<MethodSignature> reached = new LinkedHashSet<>();

    record Edge(MethodSignature from, MethodSignature to, int line) {
    }

    private CallGraphSearch(JavaView view, Set<MethodSignature> entryPoints) {
        this.view = view;
        this.entryPoints = entryPoints;
    }

    static CallGraphSearch build(List<Path> appRoots, List<Path> dependencies) {
        List<AnalysisInputLocation> locations = new ArrayList<>();
        appRoots.forEach(p -> locations.add(PathBasedAnalysisInputLocation.create(p, SourceType.Application)));
        dependencies.forEach(p -> locations.add(PathBasedAnalysisInputLocation.create(p, SourceType.Library)));
        JavaView view = new JavaView(locations);
        Set<String> appClasses = new HashSet<>();
        appRoots.forEach(r -> appClasses.addAll(classNames(r)));
        Set<MethodSignature> entries = new LinkedHashSet<>();
        for (String name : appClasses) {
            Optional<JavaSootClass> c = view.getClass(view.getIdentifierFactory().getClassType(name));
            if (c.isEmpty()) {
                continue;
            }
            for (SootMethod m : c.get().getMethods()) {
                if (m.isConcrete() && (m.isPublic() || m.isMain(view.getIdentifierFactory()))) {
                    entries.add(m.getSignature());
                }
            }
        }
        CallGraphSearch search = new CallGraphSearch(view, entries);
        search.compute();
        return search;
    }

    private void compute() {
        Set<MethodSignature> roots = new LinkedHashSet<>(entryPoints);
        Map<MethodSignature, List<Edge>> lambdaEdges = new HashMap<>();
        Set<MethodSignature> scanned = new HashSet<>();
        CallGraph cg = null;
        for (int round = 0; round < MAX_ROUNDS && !roots.isEmpty(); round++) {
            checkInterrupted();
            cg = new ClassHierarchyAnalysisAlgorithm(view).initialize(new ArrayList<>(roots));
            boolean grew = false;
            for (MethodSignature m : cg.getMethodSignatures()) {
                if (!scanned.add(m)) {
                    continue;
                }
                for (Edge e : lambdaTargets(m)) {
                    lambdaEdges.computeIfAbsent(m, k -> new ArrayList<>()).add(e);
                    if (!cg.containsMethod(e.to()) && roots.add(e.to())) {
                        grew = true;
                    }
                }
            }
            if (!grew) {
                break;
            }
        }
        if (cg != null) {
            for (CallGraph.Call call : cg.getCalls()) {
                edges.computeIfAbsent(call.sourceMethodSignature(), k -> new ArrayList<>())
                        .add(new Edge(call.sourceMethodSignature(), call.targetMethodSignature(), call.getLineNumber()));
            }
        }
        lambdaEdges.forEach((from, list) -> edges.computeIfAbsent(from, k -> new ArrayList<>()).addAll(list));
        // Breadth first from all entry points, so each method keeps a shortest path back to one of them.
        ArrayDeque<MethodSignature> queue = new ArrayDeque<>();
        for (MethodSignature e : entryPoints) {
            if (reached.add(e)) {
                queue.add(e);
            }
        }
        while (!queue.isEmpty()) {
            checkInterrupted();
            MethodSignature m = queue.poll();
            for (Edge e : edges.getOrDefault(m, List.of())) {
                if (reached.add(e.to())) {
                    parent.put(e.to(), e);
                    queue.add(e.to());
                }
            }
        }
    }

    /** The bodies a method's lambdas and method references point to. */
    private List<Edge> lambdaTargets(MethodSignature m) {
        Optional<? extends SootMethod> method = view.getMethod(m);
        if (method.isEmpty() || !method.get().hasBody()) {
            return List.of();
        }
        List<Edge> out = new ArrayList<>();
        for (Stmt s : method.get().getBody().getStmts()) {
            if (!(s instanceof InvokableStmt inv)) {
                continue;
            }
            Optional<AbstractInvokeExpr> expr = inv.getInvokeExpr();
            if (expr.isEmpty() || !(expr.get() instanceof JDynamicInvokeExpr dyn)
                    || !dyn.getBootstrapMethodSignature().getDeclClassType().getFullyQualifiedName().equals(LAMBDA_METAFACTORY)) {
                continue;
            }
            for (var arg : dyn.getBootstrapArgs()) {
                if (arg instanceof MethodHandle h && h.isMethodRef()
                        && h.getReferenceSignature() instanceof MethodSignature target) {
                    out.add(new Edge(m, target, s.getPositionInfo().getStmtPosition().getFirstLine()));
                }
            }
        }
        return out;
    }

    /** Reached methods declared in one of {@code classes}, in breadth-first order (nearest first). */
    List<MethodSignature> reachedIn(Predicate<MethodSignature> filter) {
        return reached.stream().filter(filter).toList();
    }

    int reachedCount() {
        return reached.size();
    }

    int entryPointCount() {
        return entryPoints.size();
    }

    CallPath pathTo(MethodSignature target) {
        List<Frame> frames = new ArrayList<>();
        frames.add(new Frame(symbol(target), 0));
        MethodSignature cur = target;
        Set<MethodSignature> seen = new HashSet<>();
        while (parent.containsKey(cur) && seen.add(cur)) {
            Edge e = parent.get(cur);
            frames.addFirst(new Frame(symbol(e.from()), Math.max(e.line(), 0)));
            cur = e.from();
        }
        return new CallPath(frames);
    }

    /** {@code com.acme.Parser.parse(java.lang.String)}. */
    static String symbol(MethodSignature m) {
        return m.getDeclClassType().getFullyQualifiedName() + "." + m.getName() + "("
                + m.getParameterTypes().stream().map(Object::toString).collect(Collectors.joining(",")) + ")";
    }

    /** Binary class names (with {@code $} for nested classes) in a jar or classes directory. */
    static Set<String> classNames(Path root) {
        Set<String> names = new HashSet<>();
        try {
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root)) {
                    s.filter(p -> p.toString().endsWith(".class"))
                            .forEach(p -> addClass(names, root.relativize(p).toString().replace('\\', '/')));
                }
            } else if (Files.isRegularFile(root)) {
                try (ZipFile zip = new ZipFile(root.toFile())) {
                    zip.stream().map(ZipEntry::getName).filter(n -> n.endsWith(".class")).forEach(n -> addClass(names, n));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    private static void addClass(Collection<String> names, String entry) {
        String name = entry;
        if (name.startsWith("META-INF/versions/")) {
            int next = name.indexOf('/', "META-INF/versions/".length());
            name = next < 0 ? name : name.substring(next + 1);
        }
        if (name.equals("module-info.class") || name.endsWith("/module-info.class") || name.endsWith("package-info.class")) {
            return;
        }
        names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    static final class CancelledException extends RuntimeException {
    }
}
