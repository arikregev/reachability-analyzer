package io.github.arikregev.reach.common;

/** Scan-level reason codes (architecture.md 5.4a f). Separate from the finding reason codes of 5.6. */
public enum ReasonCode {
    DT_PROJECT_NOT_FOUND(false),
    DT_UUID_MISMATCH(false),
    DT_REPO_MISMATCH(false),
    DT_REPO_UNBOUND(false),
    COMMIT_NOT_FOUND(false),
    SOURCE_TOO_LARGE(false),
    DEADLINE_EXCEEDED(false),
    INVALID_SUMMARY(false),
    DT_UNAVAILABLE(true),
    GITHUB_UNAVAILABLE(true),
    S3_UNAVAILABLE(true),
    POD_DISRUPTED(true),
    JOB_LAUNCH_FAILED(true),
    /** Artifactory refused or failed to mint the scan Job's token (D-044). */
    ARTIFACTORY_UNAVAILABLE(true),
    /** An unexpected controller error (a bug or bad data); retried up to {@code reach.retry.max-attempts}. */
    INTERNAL_ERROR(true);

    private final boolean retryable;

    ReasonCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
