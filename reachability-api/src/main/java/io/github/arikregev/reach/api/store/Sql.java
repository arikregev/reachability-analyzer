package io.github.arikregev.reach.api.store;

import java.sql.SQLException;
import java.sql.SQLTransientException;

final class Sql {

    private Sql() {
    }

    /** Connection loss, shutdown or a transient error becomes {@link StoreUnavailableException}. */
    static RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        boolean transientError = e instanceof SQLTransientException
                || (state != null && (state.startsWith("08") || state.startsWith("57P") || state.startsWith("53")
                        || state.equals("40001") || state.equals("40P01")));
        return transientError ? new StoreUnavailableException(e) : new IllegalStateException(e.getMessage(), e);
    }
}
