package io.github.arikregev.reach.controller.artifactory;

import io.github.arikregev.reach.common.ReasonCode;

/** {@code ARTIFACTORY_UNAVAILABLE}: the scan Job's token could not be minted. Retryable. */
public class ArtifactoryException extends RuntimeException {

    public ArtifactoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReasonCode reason() {
        return ReasonCode.ARTIFACTORY_UNAVAILABLE;
    }
}
