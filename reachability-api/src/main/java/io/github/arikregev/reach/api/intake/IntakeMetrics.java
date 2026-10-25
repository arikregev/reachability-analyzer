package io.github.arikregev.reach.api.intake;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

/** {@code reach_intake_requests_total{source, outcome, code}}; outcome is accepted, duplicate or rejected. */
@ApplicationScoped
public class IntakeMetrics {

    private final MeterRegistry registry;

    public IntakeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void accepted(Caller.Source source, boolean duplicate) {
        count(source, duplicate ? "duplicate" : "accepted", "none");
    }

    public void rejected(Caller.Source source, String code) {
        count(source, "rejected", code);
    }

    private void count(Caller.Source source, String outcome, String code) {
        registry.counter("reach.intake.requests", "source", source.name().toLowerCase(), "outcome", outcome,
                "code", code).increment();
    }
}
