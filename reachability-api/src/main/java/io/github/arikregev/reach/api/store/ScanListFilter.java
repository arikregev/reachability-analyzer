package io.github.arikregev.reach.api.store;

import io.github.arikregev.reach.api.intake.ScanStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Filters of {@code GET /api/v1/scans}; null means "any". {@code afterCreatedAt}/{@code afterId} is the keyset
 * position of the previous page (order is created_at, id descending).
 */
public record ScanListFilter(
        String org,
        String repo,
        String commitSha,
        Set<ScanStatus> statuses,
        Instant afterCreatedAt,
        UUID afterId) {
}
