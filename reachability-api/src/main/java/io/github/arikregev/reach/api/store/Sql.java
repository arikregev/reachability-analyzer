package io.github.arikregev.reach.api.store;

import java.sql.SQLException;

final class Sql {

    private Sql() {
    }

    /** Connection loss, shutdown or a transient error becomes {@link StoreUnavailableException}. */
    static RuntimeException translate(SQLException e) {
        return StoreUnavailableException.isTransient(e) ? new StoreUnavailableException(e)
                : new IllegalStateException(e.getMessage(), e);
    }
}
