package io.github.arikregev.reach.api.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.contracts.WriteBackMode;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** JDBC access to {@code scan.scan}, {@code scan.request_key}, {@code scan.scan_queue} and {@code scan.outbox}. */
@ApplicationScoped
public class ScanStore {

    private static final String SCAN_COLUMNS = """
            id, created_at, request_uid, repo_url, commit_sha, dt_project_name, dt_project_version, dt_project_uuid,
            priority_class, dt_write_back, status, reason, reason_detail, attempt, enqueued_at, claimed_at,
            finished_at, image_digest, reused_from, options""";

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public ScanStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    /** The database clock; all rows of one intake share it so the scan lands in the right partition. */
    public Instant now() {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT now()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    /** Claims {@code requestUid} for a new scan. False when another scan already holds it. */
    public boolean insertRequestKey(String requestUid, UUID scanId, Instant scanCreatedAt) {
        String sql = """
                INSERT INTO scan.request_key (request_uid, scan_id, scan_created_at) VALUES (?, ?, ?)
                ON CONFLICT (request_uid) DO NOTHING""";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, requestUid);
            ps.setObject(2, scanId);
            ps.setTimestamp(3, Timestamp.from(scanCreatedAt));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    public Optional<ScanRow> findByRequestUid(String requestUid) {
        String sql = "SELECT " + prefixed("s") + " FROM scan.request_key k "
                + "JOIN scan.scan s ON s.id = k.scan_id AND s.created_at = k.scan_created_at WHERE k.request_uid = ?";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, requestUid);
            return single(ps);
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    public Optional<ScanRow> findById(UUID id) {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT " + SCAN_COLUMNS + " FROM scan.scan WHERE id = ?")) {
            ps.setObject(1, id);
            return single(ps);
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    public void insertQueued(NewScan s) {
        String scanSql = """
                INSERT INTO scan.scan (id, created_at, request_uid, source, submitted_by, org, repo, repo_url,
                    commit_sha, dt_project_name, dt_project_version, dt_project_uuid, priority_class, dt_write_back,
                    status, attempt, enqueued_at, options, request)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 1, ?, ?::jsonb, ?::jsonb)""";
        String queueSql = """
                INSERT INTO scan.scan_queue (scan_id, scan_created_at, priority_class, org, repo, enqueued_at,
                    not_before, attempt)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)""";
        Timestamp created = Timestamp.from(s.createdAt());
        try (Connection c = connection(); PreparedStatement scan = c.prepareStatement(scanSql);
             PreparedStatement queue = c.prepareStatement(queueSql)) {
            int i = 1;
            scan.setObject(i++, s.id());
            scan.setTimestamp(i++, created);
            scan.setString(i++, s.requestUid());
            scan.setString(i++, s.source().name());
            scan.setString(i++, s.submittedBy());
            scan.setString(i++, s.org());
            scan.setString(i++, s.repo());
            scan.setString(i++, s.repoUrl());
            scan.setString(i++, s.commitSha());
            scan.setString(i++, s.dtProjectName());
            scan.setString(i++, s.dtProjectVersion());
            scan.setObject(i++, s.dtProjectUuid());
            scan.setString(i++, s.priorityClass());
            scan.setString(i++, s.dtWriteBack().name());
            scan.setTimestamp(i++, created);
            scan.setString(i++, s.options());
            scan.setString(i, s.request());
            scan.executeUpdate();

            queue.setObject(1, s.id());
            queue.setTimestamp(2, created);
            queue.setString(3, s.priorityClass());
            queue.setString(4, s.org());
            queue.setString(5, s.repo());
            queue.setTimestamp(6, created);
            queue.setTimestamp(7, created);
            queue.executeUpdate();
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    public void insertOutbox(String topic, String key, String payloadJson) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO scan.outbox (topic, key, payload) VALUES (?, ?, ?::jsonb)")) {
            ps.setString(1, topic);
            ps.setString(2, key);
            ps.setString(3, payloadJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    /** Up to {@code limit} scans, newest first. */
    public List<ScanRow> list(ScanListFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SCAN_COLUMNS).append(" FROM scan.scan WHERE true");
        List<Object> args = new ArrayList<>();
        if (f.org() != null) {
            sql.append(" AND org = ? AND repo = ?");
            args.add(f.org());
            args.add(f.repo());
        }
        if (f.commitSha() != null) {
            sql.append(" AND commit_sha = ?");
            args.add(f.commitSha());
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            sql.append(" AND status = ANY (?)");
            args.add(f.statuses().stream().map(Enum::name).toArray(String[]::new));
        }
        if (f.afterCreatedAt() != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(f.afterCreatedAt()));
            args.add(f.afterId());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                if (a instanceof String[] arr) {
                    ps.setArray(i + 1, c.createArrayOf("text", arr));
                } else {
                    ps.setObject(i + 1, a);
                }
            }
            List<ScanRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(map(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    private Connection connection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new StoreUnavailableException(e);
        }
    }

    private Optional<ScanRow> single(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    private ScanRow map(ResultSet rs) throws SQLException {
        try {
            return new ScanRow(
                    rs.getObject("id", UUID.class),
                    instant(rs, "created_at"),
                    rs.getString("request_uid"),
                    rs.getString("repo_url"),
                    rs.getString("commit_sha"),
                    rs.getString("dt_project_name"),
                    rs.getString("dt_project_version"),
                    rs.getObject("dt_project_uuid", UUID.class),
                    rs.getString("priority_class"),
                    WriteBackMode.valueOf(rs.getString("dt_write_back")),
                    ScanStatus.valueOf(rs.getString("status")),
                    rs.getString("reason"),
                    rs.getString("reason_detail"),
                    rs.getInt("attempt"),
                    instant(rs, "enqueued_at"),
                    instant(rs, "claimed_at"),
                    instant(rs, "finished_at"),
                    rs.getString("image_digest"),
                    rs.getObject("reused_from", UUID.class),
                    mapper.readTree(rs.getString("options")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt options json", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String prefixed(String alias) {
        return String.join(", ", Arrays.stream(SCAN_COLUMNS.split(","))
                .map(String::strip).map(col -> alias + "." + col).toList());
    }
}
