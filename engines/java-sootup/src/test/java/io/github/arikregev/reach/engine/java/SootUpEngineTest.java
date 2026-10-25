package io.github.arikregev.reach.engine.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.arikregev.reach.engine.Ecosystem;
import io.github.arikregev.reach.engine.EngineResult;
import io.github.arikregev.reach.engine.FindingReason;
import io.github.arikregev.reach.engine.FindingVerdict;
import io.github.arikregev.reach.engine.Frame;
import io.github.arikregev.reach.engine.Level;
import io.github.arikregev.reach.engine.ModuleDescriptor;
import io.github.arikregev.reach.engine.PreparedModule;
import io.github.arikregev.reach.engine.TargetFinding;
import io.github.arikregev.reach.engine.Verdict;
import io.github.arikregev.reach.engine.VulnerableSymbol;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verdict rules of the Java engine on compiled fixtures: a vulnerable library, an unused one, and an app. */
class SootUpEngineTest {

    @TempDir
    static Path tmp;

    static Path parserJar;
    static Path unusedJar;
    static Path app;

    static final String PARSER = "pkg:maven/com.example.vuln/parser@1.0.0?type=jar";
    static final String UNUSED = "pkg:maven/com.example/unused@2.0";

    @BeforeAll
    static void fixtures() {
        Path parserClasses = Fixtures.compile(tmp.resolve("parser-classes"), Map.of(
                "com/example/vuln/Parser.java", """
                        package com.example.vuln;
                        public class Parser {
                            public static String parse(String s) { return s.trim(); }
                            public static String parse(String s, int n) { return s.substring(n); }
                            public static String safe(String s) { return s; }
                        }""",
                "com/example/vuln/Handler.java", """
                        package com.example.vuln;
                        public interface Handler { void handle(String s); }""",
                "com/example/vuln/EvalHandler.java", """
                        package com.example.vuln;
                        public class EvalHandler implements Handler {
                            public void handle(String s) { Evaluator.eval(s); }
                        }""",
                "com/example/vuln/Evaluator.java", """
                        package com.example.vuln;
                        public class Evaluator { static void eval(String s) { } }"""), List.of());
        // The Maven local repository layout, as the build leaves it.
        parserJar = Fixtures.jar(parserClasses, tmp.resolve("m2/com/example/vuln/parser/1.0.0/parser-1.0.0.jar"),
                "com.example.vuln", "parser", "1.0.0");
        Path unusedClasses = Fixtures.compile(tmp.resolve("unused-classes"), Map.of(
                "com/example/unused/Thing.java", "package com.example.unused; public class Thing { public static void x() { } }"),
                List.of());
        unusedJar = Fixtures.jar(unusedClasses, tmp.resolve("lib/unused.jar"), "com.example", "unused", "2.0");
        app = Fixtures.compile(tmp.resolve("app-classes"), Map.of(
                "app/Main.java", """
                        package app;
                        import com.example.vuln.Parser;
                        import java.util.function.Function;
                        public class Main {
                            public static void main(String[] args) { new Main().run(args[0]); }
                            void run(String in) {
                                Runnable r = () -> Parser.parse(in);
                                r.run();
                                Function<String, String> f = Parser::safe;
                            }
                            private void dispatch(com.example.vuln.Handler h) { h.handle("x"); }
                        }"""), List.of(parserJar));
    }

    private static TargetFinding target(String purl, VulnerableSymbol... symbols) {
        return new TargetFinding(UUID.randomUUID(), UUID.randomUUID(), purl, "CVE-2026-0001", List.of(), List.of(symbols));
    }

    private static PreparedModule module() {
        ModuleDescriptor d = new ModuleDescriptor(tmp, "", Ecosystem.MAVEN, "maven");
        return new PreparedModule(d, "test", List.of(new PreparedModule.Unit(".", List.of(app), List.of(parserJar, unusedJar))), List.of());
    }

    private static FindingVerdict analyse(TargetFinding t) {
        EngineResult r = new SootUpEngine().analyse(module(), List.of(t), Duration.ofMinutes(1));
        assertThat(r.complete()).isTrue();
        return r.verdicts().getFirst();
    }

    @Test
    void methodReachedThroughALambdaIsReachableWithItsPath() {
        FindingVerdict v = analyse(target(PARSER, new VulnerableSymbol("com.example.vuln.Parser.parse", VulnerableSymbol.Kind.METHOD)));

        assertThat(v.verdict()).isEqualTo(Verdict.REACHABLE);
        assertThat(v.level()).isEqualTo(Level.SYMBOL);
        assertThat(v.reason()).isEqualTo(FindingReason.SYMBOL_REACHED);
        List<Frame> frames = v.paths().getFirst().frames();
        assertThat(frames.getFirst().symbol()).startsWith("app.Main.");
        assertThat(frames.getLast().symbol()).isEqualTo("com.example.vuln.Parser.parse(java.lang.String)");
        assertThat(frames).anySatisfy(f -> assertThat(f.symbol()).startsWith("app.Main.lambda$run$"));
        assertThat(frames.get(frames.size() - 2).line()).isPositive();
    }

    @Test
    void methodReferenceCountsAsAReference() {
        FindingVerdict v = analyse(target(PARSER, new VulnerableSymbol("com.example.vuln.Parser.safe", VulnerableSymbol.Kind.METHOD)));

        assertThat(v.verdict()).isEqualTo(Verdict.REACHABLE);
    }

    @Test
    void vulnerableMethodNeverCalledIsNotReachable() {
        FindingVerdict v = analyse(target(PARSER, new VulnerableSymbol("com.example.vuln.Evaluator.eval", VulnerableSymbol.Kind.METHOD)));

        // Only the private dispatch method calls Handler.handle, and nothing calls dispatch.
        assertThat(v.verdict()).isEqualTo(Verdict.NOT_REACHABLE);
        assertThat(v.level()).isEqualTo(Level.SYMBOL);
        assertThat(v.reason()).isEqualTo(FindingReason.SYMBOL_NOT_REACHED);
        assertThat(v.paths()).isEmpty();
    }

    @Test
    void reachedClassSymbolIsPotentiallyReachable() {
        FindingVerdict v = analyse(target(PARSER, new VulnerableSymbol("com.example.vuln.Parser", VulnerableSymbol.Kind.CLASS)));

        assertThat(v.verdict()).isEqualTo(Verdict.POTENTIALLY_REACHABLE);
        assertThat(v.level()).isEqualTo(Level.CLASS_OR_PACKAGE);
    }

    @Test
    void withoutSymbolsAReferencedJarIsPotentiallyReachable() {
        FindingVerdict v = analyse(target(PARSER));

        assertThat(v.verdict()).isEqualTo(Verdict.POTENTIALLY_REACHABLE);
        assertThat(v.reason()).isEqualTo(FindingReason.NO_SYMBOL_DATA);
        assertThat(v.level()).isEqualTo(Level.CLASS_OR_PACKAGE);
        assertThat(v.paths()).isNotEmpty();
    }

    @Test
    void withoutSymbolsAnUnreferencedJarIsNotReachable() {
        // Matched through its pom.properties, since its path is not a repository layout.
        FindingVerdict v = analyse(target(UNUSED));

        assertThat(v.verdict()).isEqualTo(Verdict.NOT_REACHABLE);
        assertThat(v.reason()).isEqualTo(FindingReason.COMPONENT_NOT_REFERENCED);
    }

    @Test
    void componentsOutsideTheClasspathAreLeftToTheRunner() {
        EngineResult r = new SootUpEngine().analyse(module(),
                List.of(target("pkg:maven/org.other/absent@1.0"), target("pkg:npm/left-pad@1.0.0")), Duration.ofMinutes(1));

        assertThat(r.verdicts()).isEmpty();
    }

    @Test
    void anExhaustedBudgetGivesEngineTimeout() {
        EngineResult r = new SootUpEngine().analyse(module(), List.of(target(PARSER)), Duration.ZERO);

        assertThat(r.complete()).isFalse();
        assertThat(r.verdicts()).singleElement().satisfies(v -> {
            assertThat(v.verdict()).isEqualTo(Verdict.UNKNOWN);
            assertThat(v.reason()).isEqualTo(FindingReason.ENGINE_TIMEOUT);
        });
    }
}
