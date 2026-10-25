package io.github.arikregev.reach.api.intake;

import java.util.Set;

/**
 * Who submitted a request. REST callers carry their OIDC roles; Kafka records carry no verifiable identity (5.4a j).
 */
public record Caller(Source source, String principal, Set<String> roles) {

    public enum Source {
        REST,
        KAFKA
    }

    public static Caller kafka() {
        return new Caller(Source.KAFKA, null, Set.of());
    }
}
