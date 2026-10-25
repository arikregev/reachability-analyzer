package io.github.arikregev.reach.controller.leader;

import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Leader election with a session-level Postgres advisory lock (architecture.md 3.2). The lock lives on one
 * dedicated connection outside the Agroal pool, opened with TCP keepalive and a socket timeout, so a half-open
 * connection is noticed within a bounded time. When that connection breaks, Postgres releases the lock and this
 * replica stops leading. The claim query uses SKIP LOCKED, so a short overlap during handover is safe (D-006).
 */
@ApplicationScoped
public class LeaderLock {

    private static final Logger LOG = Logger.getLogger(LeaderLock.class);

    private final long lockKey;
    private final int checkTimeoutSeconds;
    private final Event<LeadershipAcquired> acquired;
    private final Event<LeadershipLost> lost;
    private Connection held;
    /** Read without the monitor, so callers never wait on a slow check. */
    private volatile boolean leader;

    public LeaderLock(ControllerConfig config, Event<LeadershipAcquired> acquired, Event<LeadershipLost> lost,
                      MeterRegistry registry) {
        this.lockKey = config.leader().lockKey();
        this.checkTimeoutSeconds = (int) Math.max(1, config.leader().checkTimeout().toSeconds());
        this.acquired = acquired;
        this.lost = lost;
        registry.gauge("reach.controller.leader", this, l -> l.isLeader() ? 1 : 0);
    }

    public boolean isLeader() {
        return leader;
    }

    /** Checks the held lock, or tries to take it. */
    @Scheduled(every = "{reach.leader.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void tick() {
        boolean becameLeader;
        boolean steppedDown = false;
        synchronized (this) {
            if (held != null) {
                if (valid(held)) {
                    return;
                }
                LOG.warn("Lost the leader lock connection; stepping down");
                leader = false;
                closeQuietly(held);
                held = null;
                steppedDown = true;
            }
            becameLeader = tryAcquire();
        }
        if (steppedDown && !becameLeader) {
            lost.fireAsync(new LeadershipLost());
        }
        if (becameLeader) {
            LOG.info("Acquired the leader lock; this replica is the active controller");
            acquired.fireAsync(new LeadershipAcquired());
        }
    }

    private boolean tryAcquire() {
        Connection c = null;
        try {
            c = open();
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        held = c;
                        leader = true;
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.debugf("Leader lock not available: %s", e.getMessage());
        }
        closeQuietly(c);
        return false;
    }

    /** A connection of its own, from the datasource's JDBC settings. */
    private Connection open() throws SQLException {
        Config config = ConfigProvider.getConfig();
        Properties props = new Properties();
        config.getOptionalValue("quarkus.datasource.username", String.class).ifPresent(u -> props.setProperty("user", u));
        config.getOptionalValue("quarkus.datasource.password", String.class)
                .ifPresent(p -> props.setProperty("password", p));
        props.setProperty("ApplicationName", "scan-controller-leader-lock");
        props.setProperty("tcpKeepAlive", "true");
        props.setProperty("connectTimeout", Integer.toString(checkTimeoutSeconds * 2));
        props.setProperty("socketTimeout", Integer.toString(checkTimeoutSeconds * 3));
        return DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class), props);
    }

    @PreDestroy
    synchronized void release() {
        leader = false;
        if (held != null) {
            try (PreparedStatement ps = held.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                ps.execute();
            } catch (SQLException e) {
                LOG.debugf("Unlock failed: %s", e.getMessage());
            }
            closeQuietly(held);
            held = null;
        }
    }

    private boolean valid(Connection c) {
        try {
            return c.isValid(checkTimeoutSeconds);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // The connection is already broken.
            }
        }
    }
}
