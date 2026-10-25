package io.github.arikregev.reach.api.intake;

/**
 * Every priority class is disabled, so no request can be queued. A configuration error, not an outage: REST answers
 * 503 MISCONFIGURED, and Kafka retries like an outage so no record is lost while an admin re-enables a class.
 */
public class NoPriorityClassException extends RuntimeException {

    public NoPriorityClassException() {
        super("no priority class is enabled in scan.priority_class; enable at least one (default) to accept requests");
    }
}
