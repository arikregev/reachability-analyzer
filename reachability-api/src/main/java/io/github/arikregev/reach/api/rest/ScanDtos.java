package io.github.arikregev.reach.api.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.api.intake.Warning;
import io.github.arikregev.reach.contracts.WriteBackMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** REST bodies of architecture.md 5.4a b. {@code scanId} keeps its 5.3 name; every other field is snake_case. */
public final class ScanDtos {

    private ScanDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Accepted(
            @JsonProperty("scanId") UUID scanId,
            String requestUid,
            ScanStatus status,
            boolean duplicate,
            String priorityClass,
            WriteBackMode dtWriteBack,
            Instant createdAt,
            List<Warning> warnings) {
    }

    public record Repo(String url, String commit) {
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record DtProject(String name, String version, @JsonInclude(JsonInclude.Include.ALWAYS) UUID uuid) {
    }

    /** List items omit the DT uuid. */
    public record DtProjectRef(String name, String version) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Scan(
            @JsonProperty("scanId") UUID scanId,
            String requestUid,
            ScanStatus status,
            String reason,
            String reasonDetail,
            int attempt,
            Repo repo,
            DtProject dtProject,
            String priorityClass,
            WriteBackMode dtWriteBack,
            List<String> formats,
            Instant createdAt,
            Instant enqueuedAt,
            Instant claimedAt,
            Instant finishedAt,
            Map<String, Integer> counts,
            UUID reusedFrom,
            String imageDigest,
            List<Warning> warnings) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScanItem(
            @JsonProperty("scanId") UUID scanId,
            String requestUid,
            ScanStatus status,
            String reason,
            Repo repo,
            DtProjectRef dtProject,
            Instant createdAt,
            Instant finishedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScanPage(List<ScanItem> items, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {
    }
}
