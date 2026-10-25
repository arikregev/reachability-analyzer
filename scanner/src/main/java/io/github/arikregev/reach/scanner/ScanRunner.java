package io.github.arikregev.reach.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.arikregev.reach.contracts.ContractSchema;
import io.github.arikregev.reach.engine.BuildEnv;
import io.github.arikregev.reach.engine.BuildException;
import io.github.arikregev.reach.engine.Ecosystem;
import io.github.arikregev.reach.engine.EngineResult;
import io.github.arikregev.reach.engine.FindingReason;
import io.github.arikregev.reach.engine.FindingVerdict;
import io.github.arikregev.reach.engine.ModuleDescriptor;
import io.github.arikregev.reach.engine.PreparedModule;
import io.github.arikregev.reach.engine.ReachabilityEngine;
import io.github.arikregev.reach.engine.TargetFinding;
import io.github.arikregev.reach.engine.Verdict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * One scan Job (architecture.md 3.4, D-042): fetch the input bundle and the source tarball through their presigned
 * links, find the build roots, build and analyse each with the first engine that supports it, merge the verdicts
 * per finding (strongest wins, 8.3) and PUT {@code summary.json}. A module that fails to build or analyse turns into
 * UNKNOWN verdicts, never a failed Job; the Job fails only when its inputs or the upload fail: exit 13 when S3 cannot
 * be reached (retried by the controller), exit 1 otherwise. Work stops
 * {@code reach.scanner.reserve} before the deadline, and the summary then says {@code complete: false}.
 */
@ApplicationScoped
public class ScanRunner {

    private static final Logger LOG = Logger.getLogger(ScanRunner.class);
    static final int EXIT_OK = 0;
    static final int EXIT_INFRA = 1;
    /** S3 unreachable through the presigned links; the controller retries the scan (S3_UNAVAILABLE), as for fetch. */
    static final int EXIT_TRANSFER = 13;

    private final ScannerConfig config;
    private final ObjectMapper mapper;
    private final List<ReachabilityEngine> engines;
    private final String version;
    private final ContractSchema inputSchema = new ContractSchema(ContractSchema.INPUT);
    private final Transfers transfers = new Transfers();
    private final Clock clock = Clock.systemUTC();

    public ScanRunner(ScannerConfig config, ObjectMapper mapper, @Any Instance<ReachabilityEngine> engines,
                      @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev") String version) {
        this.config = config;
        this.mapper = mapper;
        this.engines = engines.stream().sorted(Comparator.comparingInt(ReachabilityEngine::rank)).toList();
        this.version = version;
    }

    public int run() {
        Instant started = clock.instant();
        Instant stopAt = started.plus(config.deadline()).minus(config.reserve());
        Path secrets = config.secretsDir();
        Path work = config.workDir();
        InputBundle input;
        Path source;
        URI summaryUrl;
        try {
            summaryUrl = Transfers.readUrl(secrets.resolve("summary-put-url"));
            JsonNode json = mapper.readTree(transfers.get("input bundle download", Transfers.readUrl(secrets.resolve("input-get-url"))));
            List<String> violations = inputSchema.validate(json);
            if (!violations.isEmpty()) {
                LOG.errorf("The input bundle is invalid: %s", violations);
                return EXIT_INFRA;
            }
            input = InputBundle.of(json);
            if (config.scanId().filter(id -> !id.isBlank()).map(id -> !UUID.fromString(id).equals(input.scanId())).orElse(false)) {
                LOG.errorf("The input bundle is for scan %s, not %s", input.scanId(), config.scanId().get());
                return EXIT_INFRA;
            }
            Files.createDirectories(work);
            Path tarball = work.resolve("source.tar.zst");
            transfers.download("source download", Transfers.readUrl(secrets.resolve("source-get-url")), tarball);
            source = extract(tarball, work.resolve("src"));
            Files.deleteIfExists(tarball);
        } catch (Transfers.TransferException e) {
            LOG.errorf("Scan inputs unavailable: %s", e.getMessage());
            return EXIT_TRANSFER;
        } catch (IOException | IllegalArgumentException e) {
            LOG.errorf("Scan inputs unusable: %s", e.getMessage());
            return EXIT_INFRA;
        }
        LOG.infof("Scan %s attempt %d: %d finding(s), commit %s", input.scanId(), input.attempt(), input.findings().size(),
                input.commit());

        List<ModuleDescriptor> found = ModuleDetector.detect(source);
        List<Summary.ModuleReport> reports = new ArrayList<>();
        Map<TargetFinding, Summary.FindingReport> merged = new LinkedHashMap<>();
        boolean complete = true;
        BuildEnv env = buildEnv(work);

        for (ModuleDescriptor module : found) {
            Summary.ModuleReport report = new Summary.ModuleReport(module);
            reports.add(report);
            Optional<ReachabilityEngine> engine = engines.stream().filter(e -> e.supports(module)).findFirst();
            if (engine.isEmpty()) {
                report.status = Summary.ModuleStatus.NO_ENGINE;
                continue;
            }
            report.engine = engine.get().id();
            report.engineVersion = engine.get().version();
            List<TargetFinding> targets = input.findings().stream()
                    .filter(t -> engine.get().ecosystems().contains(t.ecosystem())).toList();
            if (targets.isEmpty()) {
                report.status = Summary.ModuleStatus.SKIPPED;
                report.diagnostic("no findings for this ecosystem");
                continue;
            }
            Duration left = Duration.between(clock.instant(), stopAt);
            if (left.isNegative() || left.isZero()) {
                report.status = Summary.ModuleStatus.SKIPPED;
                report.diagnostic("time budget used up before this module");
                complete = false;
                targets.forEach(t -> record(merged, FindingVerdict.unknown(t, FindingReason.ENGINE_TIMEOUT), report));
                continue;
            }
            long t0 = System.nanoTime();
            try {
                PreparedModule prepared = engine.get().prepare(module, env.withTimeout(left));
                report.toolchain = prepared.toolchain();
                prepared.units().forEach(u -> report.units.add(u.name()));
                prepared.diagnostics().forEach(report::diagnostic);
                EngineResult result = engine.get().analyse(prepared, targets, positive(Duration.between(clock.instant(), stopAt)));
                result.diagnostics().forEach(report::diagnostic);
                result.verdicts().forEach(v -> record(merged, v, report));
                report.status = result.complete() ? Summary.ModuleStatus.ANALYSED : Summary.ModuleStatus.PARTIAL;
                complete &= result.complete();
            } catch (BuildException e) {
                LOG.warnf("Module %s (%s) did not build: %s", display(module), module.buildTool(), firstLine(e.getMessage()));
                report.status = Summary.ModuleStatus.BUILD_FAILED;
                report.diagnostic(e.getMessage());
                targets.forEach(t -> record(merged, FindingVerdict.unknown(t, FindingReason.BUILD_FAILED), report));
            } catch (RuntimeException e) {
                LOG.errorf(e, "Module %s: engine %s failed", display(module), engine.get().id());
                report.status = Summary.ModuleStatus.ENGINE_FAILED;
                report.diagnostic(engine.get().id() + " failed: " + e);
                targets.forEach(t -> record(merged, FindingVerdict.unknown(t, FindingReason.ENGINE_FAILED), report));
            } finally {
                report.durationMs = (System.nanoTime() - t0) / 1_000_000;
            }
        }

        Set<Ecosystem> covered = engines.stream().flatMap(e -> e.ecosystems().stream()).collect(Collectors.toSet());
        List<Summary.FindingReport> findings = new ArrayList<>();
        for (TargetFinding t : input.findings()) {
            Summary.FindingReport r = merged.get(t);
            if (r == null) {
                r = new Summary.FindingReport();
                FindingReason reason = t.ecosystem() == null || !covered.contains(t.ecosystem())
                        ? FindingReason.UNSUPPORTED_LANGUAGE : FindingReason.COMPONENT_NOT_RESOLVED;
                r.verdict = FindingVerdict.unknown(t, reason);
            }
            findings.add(r);
        }
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Summary.render(mapper, input, started, clock.instant(), complete, version,
                    config.image().orElse(null), reports, findings));
            transfers.put("summary upload", summaryUrl, body, "application/json");
        } catch (Transfers.TransferException e) {
            LOG.errorf("Summary upload failed: %s", e.getMessage());
            return EXIT_TRANSFER;
        } catch (IOException e) {
            LOG.errorf("Summary could not be written: %s", e.getMessage());
            return EXIT_INFRA;
        }
        Map<Verdict, Long> counts = findings.stream().collect(Collectors.groupingBy(f -> f.verdict.verdict(), Collectors.counting()));
        LOG.infof("Scan %s: %d module(s), verdicts %s, complete=%s, summary %d bytes", input.scanId(), reports.size(),
                counts, complete, body.length);
        return EXIT_OK;
    }

    /** Strongest verdict wins across modules (8.3); the modules list names every module that reported it. */
    private static void record(Map<TargetFinding, Summary.FindingReport> merged, FindingVerdict v, Summary.ModuleReport from) {
        Summary.FindingReport r = merged.computeIfAbsent(v.target(), k -> new Summary.FindingReport());
        String name = display(from.module);
        if (r.verdict == null || v.verdict().ordinal() > r.verdict.verdict().ordinal()
                || (v.verdict() == r.verdict.verdict() && v.level().ordinal() < r.verdict.level().ordinal())) {
            r.verdict = v;
            r.engine = from.engine;
            r.engineVersion = from.engineVersion;
        }
        r.modules.add(name);
    }

    private BuildEnv buildEnv(Path work) {
        Optional<String> token = Optional.empty();
        Path tokenFile = config.secretsDir().resolve("artifactory-token");
        if (Files.isRegularFile(tokenFile)) {
            try {
                token = Optional.of(Files.readString(tokenFile).strip()).filter(s -> !s.isEmpty());
            } catch (IOException e) {
                LOG.warnf("artifactory-token not readable: %s", e.getMessage());
            }
        }
        return new BuildEnv(work, work.resolve("home"), config.artifactory().url(), token,
                config.artifactory().repositories(), Map.of(), config.deadline());
    }

    /** Unpacks the tarball with the image's tar; owners and permissions from the archive are not kept. */
    private static Path extract(Path tarball, Path dir) throws IOException {
        Files.createDirectories(dir);
        Process p = new ProcessBuilder("tar", "--zstd", "-xf", tarball.toString(), "-C", dir.toString(),
                "--no-same-owner", "--no-same-permissions").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("tar failed: " + out.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted");
        }
        return dir;
    }

    private static Duration positive(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }

    private static String display(ModuleDescriptor m) {
        return m.path().isEmpty() ? "." : m.path();
    }

    private static String firstLine(String s) {
        int nl = s == null ? -1 : s.indexOf('\n');
        return nl < 0 ? String.valueOf(s) : s.substring(0, nl);
    }
}
