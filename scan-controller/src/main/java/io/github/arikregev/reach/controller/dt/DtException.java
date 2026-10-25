package io.github.arikregev.reach.controller.dt;

import io.github.arikregev.reach.common.ReasonCode;

/** A DT call failed; {@link #reason()} says whether the scan fails or is retried (5.4a f). */
public class DtException extends RuntimeException {

    private final ReasonCode reason;

    public DtException(ReasonCode reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ReasonCode reason() {
        return reason;
    }
}
