package io.github.arikregev.reach.api.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.contracts.WriteBackMode;
import java.time.Instant;
import java.util.UUID;

/** One {@code scan.scan} row as read back for the status and list endpoints. */
public record ScanRow(
        UUID id,
        Instant createdAt,
        String requestUid,
        String repoUrl,
        String commitSha,
        String dtProjectName,
        String dtProjectVersion,
        UUID dtProjectUuid,
        String priorityClass,
        WriteBackMode dtWriteBack,
        ScanStatus status,
        String reason,
        String reasonDetail,
        int attempt,
        Instant enqueuedAt,
        Instant claimedAt,
        Instant finishedAt,
        String imageDigest,
        UUID reusedFrom,
        JsonNode options) {
}
