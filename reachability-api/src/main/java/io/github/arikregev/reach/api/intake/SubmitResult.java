package io.github.arikregev.reach.api.intake;

import io.github.arikregev.reach.contracts.WriteBackMode;
import io.github.arikregev.reach.common.ScanStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outcome of an intake; {@code duplicate} is true when {@code metadata.uid} matched an existing scan. */
public record SubmitResult(
        UUID scanId,
        String requestUid,
        ScanStatus status,
        boolean duplicate,
        String priorityClass,
        WriteBackMode dtWriteBack,
        Instant createdAt,
        List<Warning> warnings) {
}
