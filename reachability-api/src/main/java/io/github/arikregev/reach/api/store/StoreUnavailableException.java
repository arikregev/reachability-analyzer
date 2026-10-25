package io.github.arikregev.reach.api.store;

import java.sql.SQLException;
import java.sql.SQLTransientException;

/** Postgres could not be reached or failed transiently. REST answers 503 UNAVAILABLE; Kafka retries. */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(Throwable cause) {
        super("Postgres unavailable: " + cause.getMessage(), cause);
    }

    /**
     * Whether a failure from outside our own JDBC calls (the transaction interceptor's commit, for example) is an
     * outage. The first {@link SQLException} in the cause chain decides by its SQLState; without one, a
     * {@code jakarta.transaction} exception (a rollback or heuristic outcome at commit) counts as an outage.
     */
    public static boolean isOutage(Throwable failure) {
        boolean transactionFailure = false;
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof StoreUnavailableException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                return isTransient(sql);
            }
            if (t.getClass().getName().startsWith("jakarta.transaction.")) {
                transactionFailure = true;
            }
        }
        return transactionFailure;
    }

    /** Connection loss, shutdown, resource exhaustion, serialization failure or deadlock. */
    static boolean isTransient(SQLException e) {
        String state = e.getSQLState();
        return e instanceof SQLTransientException
                || (state != null && (state.startsWith("08") || state.startsWith("57P") || state.startsWith("53")
                        || state.equals("40001") || state.equals("40P01")));
    }
}
