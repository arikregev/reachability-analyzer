package io.github.arikregev.reach.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.arikregev.reach.api.store.PolicyStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** scan.apply_retention (D-038): whole monthly partitions older than the retention period go, younger ones stay. */
@QuarkusTest
class RetentionTest {

    @Inject
    Db db;

    @Inject
    PolicyStore store;

    private boolean exists(String table) {
        return !db.query("SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'scan' AND c.relname = ?", table).isEmpty();
    }

    @Test
    void dropsPartitionsOlderThanTheRetentionPeriod() {
        db.update("CREATE TABLE IF NOT EXISTS scan.scan_p202401 PARTITION OF scan.scan "
                + "FOR VALUES FROM ('2024-01-01') TO ('2024-02-01')");
        db.update("CREATE TABLE IF NOT EXISTS scan.finding_p202401 PARTITION OF scan.finding "
                + "FOR VALUES FROM ('2024-01-01') TO ('2024-02-01')");
        String uid = "retention-" + UUID.randomUUID();
        db.update("INSERT INTO scan.request_key (request_uid, scan_id, scan_created_at) "
                + "VALUES (?, gen_random_uuid(), '2024-01-15T00:00:00Z')", uid);
        String current = db.query("SELECT 'scan_p' || to_char(now() AT TIME ZONE 'UTC', 'YYYYMM') AS p")
                .getFirst().get("p").toString();

        assertThat(store.applyRetention(Duration.ofDays(90))).isGreaterThanOrEqualTo(2);

        assertThat(exists("scan_p202401")).isFalse();
        assertThat(exists("finding_p202401")).isFalse();
        assertThat(exists(current)).isTrue();
        assertThat(db.query("SELECT 1 FROM scan.request_key WHERE request_uid = ?", uid)).isEmpty();
        assertThat(store.applyRetention(Duration.ofDays(90))).isZero();
    }

    @Test
    void refusesAPeriodShorterThanADay() {
        assertThatThrownBy(() -> store.applyRetention(Duration.ofHours(1))).hasMessageContaining("too short");
    }
}
