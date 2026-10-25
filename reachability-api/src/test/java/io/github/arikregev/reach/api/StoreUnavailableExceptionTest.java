package io.github.arikregev.reach.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.arikregev.reach.api.store.StoreUnavailableException;
import jakarta.transaction.RollbackException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Which failures from outside our JDBC calls the Kafka handler retries instead of dead-lettering. */
class StoreUnavailableExceptionTest {

    private static RuntimeException wrapped(Throwable cause) {
        return new RuntimeException("commit failed", cause);
    }

    @Test
    void connectionLossInTheCauseChainIsAnOutage() {
        assertThat(StoreUnavailableException.isOutage(wrapped(new SQLException("gone", "08006")))).isTrue();
        assertThat(StoreUnavailableException.isOutage(wrapped(new SQLException("deadlock", "40P01")))).isTrue();
    }

    @Test
    void rollbackAtCommitWithoutASqlCauseIsAnOutage() {
        assertThat(StoreUnavailableException.isOutage(wrapped(new RollbackException("rolled back")))).isTrue();
    }

    @Test
    void theSqlStateDecidesEvenInsideARollback() {
        RollbackException rollback = new RollbackException("rolled back");
        rollback.initCause(new SQLException("bad value", "22023"));
        assertThat(StoreUnavailableException.isOutage(wrapped(rollback))).isFalse();
    }

    @Test
    void recordSpecificFailuresAreNot() {
        assertThat(StoreUnavailableException.isOutage(new IllegalStateException("bad"))).isFalse();
        assertThat(StoreUnavailableException.isOutage(wrapped(new SQLException("dup", "23505")))).isFalse();
    }
}
