package io.github.arikregev.reach.controller.dt;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One DT finding: identity is (componentUuid, vulnerabilityUuid); DT has no finding UUID (D-003). */
public record DtFinding(
        UUID componentUuid,
        UUID vulnerabilityUuid,
        String vulnId,
        String source,
        List<String> aliases,
        String severity,
        String purl,
        String componentName,
        String componentVersion) {

    /** Reads one element of {@code GET /api/v1/finding/project/{uuid}}. */
    public static DtFinding from(JsonNode f) {
        JsonNode component = f.path("component");
        JsonNode vuln = f.path("vulnerability");
        String vulnId = vuln.path("vulnId").asText();
        // DT returns aliases as objects such as {"cveId": "CVE-...", "ghsaId": "GHSA-..."}.
        Set<String> aliases = new LinkedHashSet<>();
        for (JsonNode alias : vuln.path("aliases")) {
            for (Iterator<Map.Entry<String, JsonNode>> it = alias.fields(); it.hasNext(); ) {
                JsonNode value = it.next().getValue();
                if (value.isTextual() && !value.asText().isBlank() && !value.asText().equals(vulnId)) {
                    aliases.add(value.asText());
                }
            }
        }
        return new DtFinding(
                UUID.fromString(component.path("uuid").asText()),
                UUID.fromString(vuln.path("uuid").asText()),
                vulnId,
                text(vuln, "source"),
                List.copyOf(new ArrayList<>(aliases)),
                text(vuln, "severity"),
                text(component, "purl"),
                text(component, "name"),
                text(component, "version"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
