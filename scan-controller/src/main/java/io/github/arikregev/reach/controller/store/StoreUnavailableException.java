package io.github.arikregev.reach.controller.store;

/** Postgres could not be reached or failed transiently; the loops skip the round and try again. */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(Throwable cause) {
        super("Postgres unavailable: " + cause.getMessage(), cause);
    }
}
