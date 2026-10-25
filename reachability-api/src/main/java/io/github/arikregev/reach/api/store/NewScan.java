package io.github.arikregev.reach.api.store;

import io.github.arikregev.reach.api.intake.Caller;
import io.github.arikregev.reach.contracts.WriteBackMode;
import java.time.Instant;
import java.util.UUID;

/** Values for a new QUEUED scan; {@code options} and {@code request} are JSON text. */
public record NewScan(
        UUID id,
        Instant createdAt,
        String requestUid,
        Caller.Source source,
        String submittedBy,
        String org,
        String repo,
        String repoUrl,
        String commitSha,
        String dtProjectName,
        String dtProjectVersion,
        UUID dtProjectUuid,
        String priorityClass,
        WriteBackMode dtWriteBack,
        String options,
        String request) {
}
