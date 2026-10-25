package io.github.arikregev.reach.engine;

import java.util.List;

/** Verdicts for the targets the module resolves; {@code complete} is false when the budget ran out. */
public record EngineResult(List<FindingVerdict> verdicts, boolean complete, List<String> diagnostics) {

    public EngineResult {
        verdicts = List.copyOf(verdicts);
        diagnostics = List.copyOf(diagnostics);
    }
}
