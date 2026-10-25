package io.github.arikregev.reach.api.store;

/** Postgres could not be reached or failed transiently. REST answers 503 UNAVAILABLE; Kafka retries. */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(Throwable cause) {
        super("Postgres unavailable: " + cause.getMessage(), cause);
    }
}
