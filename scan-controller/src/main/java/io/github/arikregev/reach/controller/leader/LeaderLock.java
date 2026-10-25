package io.github.arikregev.reach.controller.leader;

import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Leader election with a session-level Postgres advisory lock (architecture.md 3.2). The lock lives on one
 * dedicated connection; when that connection breaks, Postgres releases the lock and this replica stops leading.
 * The claim query uses SKIP LOCKED, so a short overlap during handover is safe (D-006).
 */
@ApplicationScoped
public class LeaderLock {

    private static final Logger LOG = Logger.getLogger(LeaderLock.class);

    private final DataSource dataSource;
    private final long lockKey;
    private final Event<LeadershipAcquired> acquired;
    private Connection held;

    public LeaderLock(DataSource dataSource, ControllerConfig config, Event<LeadershipAcquired> acquired,
                      MeterRegistry registry) {
        this.dataSource = dataSource;
        this.lockKey = config.leader().lockKey();
        this.acquired = acquired;
        registry.gauge("reach.controller.leader", this, l -> l.isLeader() ? 1 : 0);
    }

    public synchronized boolean isLeader() {
        return held != null;
    }

    /** Checks the held lock, or tries to take it. */
    @Scheduled(every = "{reach.leader.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void tick() {
        boolean becameLeader;
        synchronized (this) {
            if (held != null) {
                if (valid(held)) {
                    return;
                }
                LOG.warn("Lost the leader lock connection; stepping down");
                closeQuietly(held);
                held = null;
            }
            becameLeader = tryAcquire();
        }
        if (becameLeader) {
            LOG.info("Acquired the leader lock; this replica is the active controller");
            acquired.fireAsync(new LeadershipAcquired());
        }
    }

    private boolean tryAcquire() {
        Connection c = null;
        try {
            c = dataSource.getConnection();
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        held = c;
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

    @PreDestroy
    synchronized void release() {
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

    private static boolean valid(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1"); ResultSet rs = ps.executeQuery()) {
            return rs.next();
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
