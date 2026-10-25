package io.github.arikregev.reach.controller.store;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** The transactional outbox (D-006): written with each state change, published by {@code OutboxRelay}. */
@ApplicationScoped
public class OutboxStore {

    public record Row(long id, String topic, String key, String payload, String headers) {
    }

    private final DataSource dataSource;

    public OutboxStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(String topic, String key, String payloadJson) {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO scan.outbox (topic, key, payload) VALUES (?, ?, ?::jsonb)")) {
            ps.setString(1, topic);
            ps.setString(2, key);
            ps.setString(3, payloadJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /** The oldest unpublished rows, in insertion order. */
    public List<Row> unpublished(int limit) {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement("""
                SELECT id, topic, key, payload::text, headers::text FROM scan.outbox
                WHERE published_at IS NULL ORDER BY id LIMIT ?""")) {
            ps.setInt(1, limit);
            List<Row> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    public void markPublished(List<Long> ids) {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(
                "UPDATE scan.outbox SET published_at = now() WHERE id = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    public int purgePublished(Duration olderThan) {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM scan.outbox WHERE published_at < now() - make_interval(secs => ?)")) {
            ps.setLong(1, olderThan.toSeconds());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /** Age of the oldest unpublished row in seconds, 0 when the outbox is drained ({@code reach_outbox_lag_seconds}). */
    public double lagSeconds() {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(
                "SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM scan.outbox WHERE published_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getDouble(1);
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }

    /** Queued scans per priority class ({@code reach_queue_depth}). */
    public Map<String, Integer> queueDepth() {
        try (Connection c = Db.connection(dataSource); PreparedStatement ps = c.prepareStatement(
                "SELECT pc.name, count(q.scan_id) FROM scan.priority_class pc "
                        + "LEFT JOIN scan.scan_queue q ON q.priority_class = pc.name GROUP BY pc.name");
             ResultSet rs = ps.executeQuery()) {
            Map<String, Integer> depth = new HashMap<>();
            while (rs.next()) {
                depth.put(rs.getString(1), rs.getInt(2));
            }
            return depth;
        } catch (SQLException e) {
            throw Db.translate(e);
        }
    }
}
