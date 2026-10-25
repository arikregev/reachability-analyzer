package io.github.arikregev.reach.api.intake;

import io.github.arikregev.reach.contracts.Violation;
import java.util.List;

/** A schema-valid request that breaks a 5.1 rule. REST answers 400 SCHEMA_INVALID; Kafka sends it to the DLQ. */
public class RequestRejectedException extends RuntimeException {

    private final List<Violation> violations;

    public RequestRejectedException(List<Violation> violations) {
        super(violations.size() + " rule violation(s): " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
