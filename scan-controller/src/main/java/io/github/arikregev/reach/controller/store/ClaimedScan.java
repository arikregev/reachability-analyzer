package io.github.arikregev.reach.controller.store;

import io.github.arikregev.reach.common.StatusEvents;
import java.time.Instant;
import java.util.UUID;

/** A scan the controller owns while it is FETCHING, SCANNING or PUBLISHING. */
public record ClaimedScan(
        UUID id,
        Instant createdAt,
        String requestUid,
        String org,
        String repo,
        String repoUrl,
        String commitSha,
        String dtProjectName,
        String dtProjectVersion,
        UUID dtProjectUuid,
        String priorityClass,
        int attempt,
        Instant enqueuedAt) {

    public StatusEvents.Scan eventScan() {
        return new StatusEvents.Scan(id, requestUid, dtProjectName, dtProjectVersion, dtProjectUuid);
    }

    public String fullName() {
        return org + "/" + repo;
    }
}
