package io.github.arikregev.reach.controller.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import javax.sql.DataSource;

/** Connection and error helpers shared by the stores. */
final class Db {

    private Db() {
    }

    static Connection connection(DataSource dataSource) {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new StoreUnavailableException(e);
        }
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
