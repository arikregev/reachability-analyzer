package io.github.arikregev.reach.api.store;

import io.github.arikregev.reach.api.config.ReachConfig;
import io.github.arikregev.reach.contracts.WriteBackMode;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** Reads the policy tables (D-004, D-007) and runs schema housekeeping. */
@ApplicationScoped
public class PolicyStore {

    private final DataSource dataSource;

    public PolicyStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Weights of the enabled priority classes, by name. */
    public Map<String, Integer> enabledPriorityWeights() {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT name, weight FROM scan.priority_class WHERE enabled");
             ResultSet rs = ps.executeQuery()) {
            Map<String, Integer> weights = new HashMap<>();
            while (rs.next()) {
                weights.put(rs.getString(1), rs.getInt(2));
            }
            return weights;
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    /** The most specific write-back policy mode: REPO ({@code org/repo}), then ORG, then GLOBAL ({@code *}). */
    public Optional<WriteBackMode> writeBackPolicy(String org, String fullName) {
        String sql = """
                SELECT mode FROM scan.writeback_policy
                WHERE (scope = 'REPO' AND key = ?) OR (scope = 'ORG' AND key = ?) OR (scope = 'GLOBAL' AND key = '*')
                ORDER BY CASE scope WHEN 'REPO' THEN 0 WHEN 'ORG' THEN 1 ELSE 2 END
                LIMIT 1""";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, org);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(WriteBackMode.valueOf(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    /** Inserts configured classes that are not in the table yet; rows edited through the admin API win. */
    public int seedPriorityClasses(Map<String, ReachConfig.PriorityClass> classes) {
        String sql = """
                INSERT INTO scan.priority_class (name, weight, aging_per_min, max_running) VALUES (?, ?, ?, ?)
                ON CONFLICT (name) DO NOTHING""";
        int inserted = 0;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, ReachConfig.PriorityClass> e : classes.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setInt(2, e.getValue().weight());
                ps.setDouble(3, e.getValue().agingPerMin());
                if (e.getValue().maxRunning().isPresent()) {
                    ps.setInt(4, e.getValue().maxRunning().get());
                } else {
                    ps.setNull(4, Types.INTEGER);
                }
                inserted += ps.executeUpdate();
            }
            return inserted;
        } catch (SQLException e) {
            throw Sql.translate(e);
        }
    }

    /** Creates missing monthly partitions of the partitioned scan tables; returns how many were created. */
    public int ensurePartitions(int monthsAhead) {
        String sql = "SELECT scan.ensure_monthly_partitions('scan.scan', ?) + scan.ensure_monthly_partitions('scan.finding', ?)";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, monthsAhead);
            ps.setInt(2, monthsAhead);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
}
