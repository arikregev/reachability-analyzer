package io.github.arikregev.reach.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.arikregev.reach.engine.TargetFinding;
import io.github.arikregev.reach.engine.VulnerableSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code input.json} (schema {@code reach.input.v1}), already validated against its JSON Schema. */
record InputBundle(UUID scanId, int attempt, String commit, List<TargetFinding> findings) {

    static InputBundle of(JsonNode root) {
        List<TargetFinding> findings = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            List<String> aliases = new ArrayList<>();
            f.path("aliases").forEach(a -> aliases.add(a.asText()));
            List<VulnerableSymbol> symbols = new ArrayList<>();
            f.path("symbols").forEach(s -> symbols.add(new VulnerableSymbol(s.path("symbol").asText(),
                    VulnerableSymbol.Kind.valueOf(s.path("kind").asText()))));
            findings.add(new TargetFinding(UUID.fromString(f.path("component_uuid").asText()),
                    UUID.fromString(f.path("vulnerability_uuid").asText()),
                    f.path("purl").isTextual() ? f.path("purl").asText() : null,
                    f.path("vuln_id").asText(), aliases, symbols));
        }
        return new InputBundle(UUID.fromString(root.path("scan_id").asText()), root.path("attempt").asInt(),
                root.path("repo").path("commit").asText(), findings);
    }
}
