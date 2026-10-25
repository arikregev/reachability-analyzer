package io.github.arikregev.reach.controller.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.arikregev.reach.controller.dt.DtFinding;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * {@code work/<scanId>/input.json}, what the scan Job analyses (architecture.md 4.1): the scan, its DT findings and
 * the project's CycloneDX SBOM. KB symbols are added with the knowledge base. The Job may only report on these
 * findings.
 */
@ApplicationScoped
public class InputBundle {

    public static final String SCHEMA = "reach.input.v1";

    private final ObjectMapper mapper;

    public InputBundle(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] render(ClaimedScan scan, UUID dtProjectUuid, List<DtFinding> findings, JsonNode sbom) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("scan_id", scan.id().toString());
        root.put("attempt", scan.attempt());
        ObjectNode repo = root.putObject("repo");
        repo.put("full_name", scan.fullName());
        repo.put("url", scan.repoUrl());
        repo.put("commit", scan.commitSha());
        ObjectNode dt = root.putObject("dt_project");
        dt.put("name", scan.dtProjectName());
        dt.put("version", scan.dtProjectVersion());
        dt.put("uuid", dtProjectUuid.toString());
        ArrayNode list = root.putArray("findings");
        for (DtFinding f : findings) {
            ObjectNode n = list.addObject();
            n.put("component_uuid", f.componentUuid().toString());
            n.put("vulnerability_uuid", f.vulnerabilityUuid().toString());
            n.put("vuln_id", f.vulnId());
            n.put("source", f.source());
            f.aliases().forEach(n.putArray("aliases")::add);
            n.put("severity", f.severity());
            n.put("purl", f.purl());
            n.put("component_name", f.componentName());
            n.put("component_version", f.componentVersion());
        }
        root.set("sbom", sbom);
        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
