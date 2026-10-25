package io.github.arikregev.reach.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A schema-valid OCSF Scan Activity request (architecture.md 5.1). Optional fields are null when absent.
 *
 * @param requestUid   {@code metadata.uid}, the idempotency key
 * @param scanUid      {@code scan.uid}, the emitter's scan id
 * @param repoUrl      {@code unmapped.reachability.repo.url}
 * @param formats      requested formats, or null for the default (all)
 * @param raw          the whole request as received
 */
public record ScanRequest(
        String requestUid,
        String scanUid,
        int activityId,
        long time,
        String ocsfVersion,
        String emitter,
        String repoUrl,
        String commit,
        String dtProjectName,
        String dtProjectVersion,
        UUID dtProjectUuid,
        String priority,
        List<OutputFormat> formats,
        WriteBackMode dtWriteBack,
        Duration linkTtl,
        JsonNode raw) {
}
