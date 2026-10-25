package io.github.arikregev.reach.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.arikregev.reach.engine.CallPath;
import io.github.arikregev.reach.engine.Frame;
import io.github.arikregev.reach.engine.ModuleDescriptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds {@code summary.json} (schema {@code reach.summary.v1}, architecture.md 5.5, D-042). */
final class Summary {

    static final String SCHEMA = "reach.summary.v1";
    static final int MAX_DIAGNOSTICS = 50;
    static final int MAX_DIAGNOSTIC_CHARS = 4000;
    static final int MAX_FRAMES = 64;

    enum ModuleStatus { ANALYSED, PARTIAL, BUILD_FAILED, ENGINE_FAILED, SKIPPED, NO_ENGINE }

    /** One build root's outcome. */
    static final class ModuleReport {
        final ModuleDescriptor module;
        ModuleStatus status = ModuleStatus.SKIPPED;
        String engine;
        String engineVersion;
        String toolchain;
        final List<String> units = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        long durationMs;

        ModuleReport(ModuleDescriptor module) {
            this.module = module;
        }

        void diagnostic(String d) {
            if (diagnostics.size() < MAX_DIAGNOSTICS) {
                diagnostics.add(d.length() > MAX_DIAGNOSTIC_CHARS ? d.substring(0, MAX_DIAGNOSTIC_CHARS) : d);
            }
        }
    }

    /** One finding's merged verdict and the modules it came from. */
    static final class FindingReport {
        io.github.arikregev.reach.engine.FindingVerdict verdict;
        String engine;
        String engineVersion;
        final Set<String> modules = new LinkedHashSet<>();
    }

    static ObjectNode render(ObjectMapper mapper, InputBundle input, Instant started, Instant finished, boolean complete,
                             String version, String image, List<ModuleReport> modules, List<FindingReport> findings) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("scan_id", input.scanId().toString());
        root.put("attempt", input.attempt());
        root.put("commit", input.commit());
        root.put("started_at", started.toString());
        root.put("finished_at", finished.toString());
        root.put("complete", complete);
        ObjectNode scanner = root.putObject("scanner");
        scanner.put("version", version);
        scanner.put("image", image);
        ArrayNode ms = root.putArray("modules");
        for (ModuleReport m : modules) {
            ObjectNode n = ms.addObject();
            n.put("path", m.module.path());
            n.put("ecosystem", m.module.ecosystem().purlType());
            n.put("build_tool", m.module.buildTool());
            n.put("status", m.status.name());
            n.put("engine", m.engine);
            n.put("engine_version", m.engineVersion);
            n.put("toolchain", m.toolchain);
            m.units.forEach(n.putArray("units")::add);
            n.put("duration_ms", m.durationMs);
            m.diagnostics.forEach(n.putArray("diagnostics")::add);
        }
        ArrayNode fs = root.putArray("findings");
        for (FindingReport f : findings) {
            var v = f.verdict;
            ObjectNode n = fs.addObject();
            n.put("component_uuid", v.target().componentUuid().toString());
            n.put("vulnerability_uuid", v.target().vulnerabilityUuid().toString());
            n.put("vuln_id", v.target().vulnId());
            n.put("purl", v.target().purl());
            n.put("verdict", v.verdict().name());
            n.put("reason", v.reason().name());
            n.put("level", v.level().name());
            n.put("engine", f.engine);
            n.put("engine_version", f.engineVersion);
            f.modules.forEach(n.putArray("modules")::add);
            ArrayNode paths = n.putArray("call_paths");
            for (CallPath p : v.paths().stream().limit(3).toList()) {
                ArrayNode frames = paths.addObject().putArray("frames");
                List<Frame> list = p.frames();
                // Keep both ends of an overlong path: the entry point and the vulnerable code.
                List<Frame> kept = list.size() <= MAX_FRAMES ? list
                        : concat(list.subList(0, MAX_FRAMES / 2), list.subList(list.size() - MAX_FRAMES / 2, list.size()));
                for (Frame fr : kept) {
                    ObjectNode o = frames.addObject();
                    o.put("symbol", fr.symbol().length() > 1000 ? fr.symbol().substring(0, 1000) : fr.symbol());
                    o.put("line", Math.max(fr.line(), 0));
                }
            }
        }
        return root;
    }

    private static List<Frame> concat(List<Frame> a, List<Frame> b) {
        List<Frame> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
