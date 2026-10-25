package io.github.arikregev.reach.controller;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Seeds and inspects the Dev Services Postgres the way reachability-api would leave it. */
@ApplicationScoped
public class TestDb {

    private final DataSource dataSource;

    public TestDb(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void reset() {
        update("DELETE FROM scan.scan_queue");
        update("DELETE FROM scan.finding");
        update("DELETE FROM scan.scan");
        update("DELETE FROM scan.outbox");
        update("DELETE FROM scan.concurrency_limit");
        update("DELETE FROM scan.priority_class");
        update("INSERT INTO scan.priority_class (name, weight, aging_per_min, max_running) VALUES "
                + "('critical', 1000, 1, NULL), ('pr', 500, 1, NULL), ('default', 100, 1, NULL), ('bulk', 10, 1, 20)");
    }

    /** A QUEUED scan with its queue row, as reachability-api inserts it; enqueued {@code minutesAgo} ago. */
    public UUID queue(String org, String repo, String dtName, String dtVersion, UUID dtUuid, String priority,
                      int minutesAgo) {
        UUID id = UUID.randomUUID();
        String commit = "4f1c9e2b7d3a8e6f0b5c1d2e3f4a5b6c7d8e9f0a";
        update("""
                INSERT INTO scan.scan (id, created_at, request_uid, source, org, repo, repo_url, commit_sha,
                    dt_project_name, dt_project_version, dt_project_uuid, priority_class, dt_write_back, status, attempt,
                    enqueued_at, options, request)
                VALUES (?, now(), ?, 'REST', ?, ?, ?, ?, ?, ?, ?, ?, 'OFF', 'QUEUED', 1,
                    now() - make_interval(mins => ?), '{}'::jsonb, '{}'::jsonb)""",
                id, "req-" + id, org, repo, "https://github.com/" + org + "/" + repo, commit, dtName, dtVersion, dtUuid,
                priority, minutesAgo);
        update("""
                INSERT INTO scan.scan_queue (scan_id, scan_created_at, priority_class, org, repo, enqueued_at, not_before, attempt)
                SELECT id, created_at, priority_class, org, repo, enqueued_at, now(), attempt FROM scan.scan WHERE id = ?""",
                id);
        return id;
    }

    public UUID queue(String dtName, String priority, int minutesAgo) {
        String[] parts = dtName.split("/");
        return queue(parts[0], parts[1], dtName, "main", null, priority, minutesAgo);
    }

    public Map<String, Object> scan(UUID id) {
        return query("SELECT status, reason, reason_detail, attempt, findings_hash, dt_project_uuid FROM scan.scan WHERE id = ?", id)
                .getFirst();
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
