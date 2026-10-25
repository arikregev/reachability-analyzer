package io.github.arikregev.reach.controller.store;

import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.controller.dt.DtFinding;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC access for the controller's state changes on {@code scan.scan}, {@code scan.scan_queue},
 * {@code scan.finding} and {@code scan.outbox}. Callers run each state change in one transaction, and every update
 * is guarded by the expected current status so a stale leader cannot overwrite a newer state.
 */
@ApplicationScoped
public class ScanStore {

    /** architecture.md 6.2, with config defaults for orgs and repos that have no concurrency_limit row. */
    private static final String CLAIM_SQL = """
            WITH running AS (
              SELECT org, repo, priority_class, count(*) AS n
              FROM scan.scan WHERE status IN ('FETCHING', 'SCANNING', 'PUBLISHING')
              GROUP BY org, repo, priority_class
            ), cand AS (
              SELECT q.scan_id
              FROM scan.scan_queue q
              JOIN scan.priority_class pc ON pc.name = q.priority_class AND pc.enabled
              LEFT JOIN scan.concurrency_limit lo ON lo.scope = 'ORG' AND lo.key = q.org
              LEFT JOIN scan.concurrency_limit lr ON lr.scope = 'REPO' AND lr.key = q.org || '/' || q.repo
              WHERE q.not_before <= now()
                AND (coalesce(lo.max_running, ?::int) IS NULL OR coalesce(lo.max_running, ?::int) >
                     (SELECT coalesce(sum(n), 0) FROM running r WHERE r.org = q.org))
                AND (coalesce(lr.max_running, ?::int) >
                     (SELECT coalesce(sum(n), 0) FROM running r WHERE r.org = q.org AND r.repo = q.repo))
                AND (pc.max_running IS NULL OR pc.max_running >
                     (SELECT coalesce(sum(n), 0) FROM running r WHERE r.priority_class = q.priority_class))
              ORDER BY pc.weight + pc.aging_per_min * extract(epoch FROM now() - q.enqueued_at) / 60 DESC,
                       q.enqueued_at
              LIMIT ?
              FOR UPDATE OF q SKIP LOCKED
            )
            DELETE FROM scan.scan_queue q USING cand
            WHERE q.scan_id = cand.scan_id
            RETURNING q.scan_id, q.scan_created_at""";

    private static final String SCAN_COLUMNS = """
            id, created_at, request_uid, org, repo, dt_project_name, dt_project_version, dt_project_uuid,
            priority_class, attempt, enqueued_at""";

    private final DataSource dataSource;

    public ScanStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Instant now() {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement("SELECT now()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /** Scans in FETCHING, SCANNING or PUBLISHING. */
    public int runningTotal() {
        return count("SELECT count(*) FROM scan.scan WHERE status IN ('FETCHING', 'SCANNING', 'PUBLISHING')");
    }

    /** The GLOBAL row of {@code scan.concurrency_limit}, if any. */
    public Optional<Integer> globalLimit() {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(
                "SELECT max_running FROM scan.concurrency_limit WHERE scope = 'GLOBAL' AND key = '*'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /** Claims up to {@code batch} queued scans and moves them to FETCHING (6.2). Run inside a transaction. */
    public List<ClaimedScan> claim(int batch, Integer orgDefault, int repoDefault) {
        List<UUID> ids = new ArrayList<>();
        List<Timestamp> created = new ArrayList<>();
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(CLAIM_SQL)) {
            if (orgDefault == null) {
                ps.setNull(1, Types.INTEGER);
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(1, orgDefault);
                ps.setInt(2, orgDefault);
            }
            ps.setInt(3, repoDefault);
            ps.setInt(4, batch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                    created.add(rs.getTimestamp(2));
                }
            }
        } catch (SQLException e) {
            throw Db.translate(e);
        }
        List<ClaimedScan> claimed = new ArrayList<>();
        String sql = "UPDATE scan.scan SET status = 'FETCHING', claimed_at = now(), reason = NULL, reason_detail = NULL "
                + "WHERE id = ? AND created_at = ? AND status = 'QUEUED' RETURNING " + SCAN_COLUMNS;
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setObject(1, ids.get(i));
                ps.setTimestamp(2, created.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        claimed.add(map(rs));
                    }
                }
            }
        } catch (SQLException e) {
            throw Db.translate(e);
        }
        return claimed;
    }

    /** FETCHING scans whose DT data is not loaded yet; a new leader resumes them. */
    public List<ClaimedScan> fetchingWithoutFindings() {
        String sql = "SELECT " + SCAN_COLUMNS + " FROM scan.scan WHERE status = 'FETCHING' AND findings_hash IS NULL";
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<ClaimedScan> scans = new ArrayList<>();
            while (rs.next()) {
                scans.add(map(rs));
            }
            return scans;
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /**
     * Stores the DT project uuid, the findings hash and one {@code scan.finding} row per finding. False when the
     * scan is no longer FETCHING (cancelled, or handled by another leader).
     */
    public boolean saveFindings(ClaimedScan scan, UUID dtProjectUuid, String findingsHash, List<DtFinding> findings) {
        try (Connection c = Db.connection(dataSource)) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE scan.scan SET dt_project_uuid = ?, findings_hash = ?
                    WHERE id = ? AND created_at = ? AND status = 'FETCHING'""")) {
                ps.setObject(1, dtProjectUuid);
                ps.setString(2, findingsHash);
                ps.setObject(3, scan.id());
                ps.setTimestamp(4, Timestamp.from(scan.createdAt()));
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM scan.finding WHERE scan_id = ? AND created_at = ?")) {
                ps.setObject(1, scan.id());
                ps.setTimestamp(2, Timestamp.from(scan.createdAt()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO scan.finding (scan_id, created_at, component_uuid, vulnerability_uuid, vuln_id,
                        vuln_source, aliases, severity, purl, component_name, component_version, dt_project_name,
                        dt_project_version, repo)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING""")) {
                for (DtFinding f : findings) {
                    int i = 1;
                    ps.setObject(i++, scan.id());
                    ps.setTimestamp(i++, Timestamp.from(scan.createdAt()));
                    ps.setObject(i++, f.componentUuid());
                    ps.setObject(i++, f.vulnerabilityUuid());
                    ps.setString(i++, f.vulnId());
                    ps.setString(i++, f.source());
                    ps.setArray(i++, c.createArrayOf("text", f.aliases().toArray()));
                    ps.setString(i++, f.severity());
                    ps.setString(i++, f.purl());
                    ps.setString(i++, f.componentName());
                    ps.setString(i++, f.componentVersion());
                    ps.setString(i++, scan.dtProjectName());
                    ps.setString(i++, scan.dtProjectVersion());
                    ps.setString(i, scan.fullName());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return true;
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /**
     * Puts a FETCHING scan back in the queue after a retryable failure (4.2): {@code attempt++}, same priority and
     * original {@code enqueued_at}. Returns the new attempt, or empty when the scan is no longer FETCHING.
     */
    public Optional<Integer> requeue(ClaimedScan scan, ReasonCode reason, String detail, Instant notBefore) {
        try (Connection c = Db.connection(dataSource)) {
            int attempt;
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE scan.scan SET status = 'QUEUED', reason = ?, reason_detail = ?, attempt = attempt + 1,
                        claimed_at = NULL
                    WHERE id = ? AND created_at = ? AND status = 'FETCHING' RETURNING attempt""")) {
                ps.setString(1, reason.name());
                ps.setString(2, detail);
                ps.setObject(3, scan.id());
                ps.setTimestamp(4, Timestamp.from(scan.createdAt()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    attempt = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO scan.scan_queue (scan_id, scan_created_at, priority_class, org, repo, enqueued_at,
                        not_before, attempt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setObject(1, scan.id());
                ps.setTimestamp(2, Timestamp.from(scan.createdAt()));
                ps.setString(3, scan.priorityClass());
                ps.setString(4, scan.org());
                ps.setString(5, scan.repo());
                ps.setTimestamp(6, Timestamp.from(scan.enqueuedAt()));
                ps.setTimestamp(7, Timestamp.from(notBefore));
                ps.setInt(8, attempt);
                ps.executeUpdate();
            }
            return Optional.of(attempt);
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /** Moves a FETCHING scan to FAILED. False when the scan is no longer FETCHING. */
    public boolean fail(ClaimedScan scan, ReasonCode reason, String detail) {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement("""
                UPDATE scan.scan SET status = 'FAILED', reason = ?, reason_detail = ?, finished_at = now()
                WHERE id = ? AND created_at = ? AND status = 'FETCHING'""")) {
            ps.setString(1, reason.name());
            ps.setString(2, detail);
            ps.setObject(3, scan.id());
            ps.setTimestamp(4, Timestamp.from(scan.createdAt()));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    private int count(String sql) {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    private static ClaimedScan map(ResultSet rs) throws SQLException {
        return new ClaimedScan(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("request_uid"),
                rs.getString("org"),
                rs.getString("repo"),
                rs.getString("dt_project_name"),
                rs.getString("dt_project_version"),
                rs.getObject("dt_project_uuid", UUID.class),
                rs.getString("priority_class"),
                rs.getInt("attempt"),
                rs.getTimestamp("enqueued_at").toInstant());
    }
}
