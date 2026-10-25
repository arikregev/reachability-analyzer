package io.github.arikregev.reach.controller.github;

import io.github.arikregev.reach.common.ReasonCode;

/** A GitHub call failed: {@code COMMIT_NOT_FOUND} (permanent) or {@code GITHUB_UNAVAILABLE} (retryable). */
public class GitHubException extends RuntimeException {

    private final ReasonCode reason;

    public GitHubException(ReasonCode reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ReasonCode reason() {
        return reason;
    }
}
