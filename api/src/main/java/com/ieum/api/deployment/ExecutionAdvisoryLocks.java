package com.ieum.api.deployment;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.ieum.workflowcore.engine.ExecutionOwnershipGuard;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Session-scoped locks cannot expire while a healthy instance is still executing a job. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class ExecutionAdvisoryLocks implements ExecutionOwnershipGuard {
    private final DataSource dataSource;
    private final ConcurrentMap<UUID, Lease> owned = new ConcurrentHashMap<>();

    public Lease tryAcquire(UUID executionId) throws SQLException {
        if (owned.containsKey(executionId)) return null;
        long key = executionId.getMostSignificantBits() ^ executionId.getLeastSignificantBits();
        Connection connection = dataSource.getConnection();
        Lease lease = null;
        try {
            // This dedicated connection never participates in a workflow transaction.
            if (!connection.getAutoCommit()) connection.setAutoCommit(true);
            boolean acquired;
            int backendPid;
            Timestamp backendStartedAt;
            try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?), "
                    + "pg_backend_pid(), (select backend_start from pg_stat_activity where pid = pg_backend_pid())")) {
                statement.setLong(1, key);
                try (var result = statement.executeQuery()) {
                    acquired = result.next() && result.getBoolean(1);
                    backendPid = acquired ? result.getInt(2) : 0;
                    backendStartedAt = acquired ? result.getTimestamp(3) : null;
                }
            }
            // Resource close can throw and cancel a return. Register only after both JDBC resources
            // have closed successfully, otherwise a caller that never received a lease cannot free it.
            if (acquired) {
                if (backendPid <= 0 || backendStartedAt == null)
                    throw new SQLException("Cannot identify advisory lock session");
                lease = new Lease(connection, key, executionId, backendPid, backendStartedAt);
                if (owned.putIfAbsent(executionId, lease) == null) return lease;
                lease.close();
                return null;
            }
            connection.close();
            return null;
        } catch (SQLException | RuntimeException | Error failure) {
            if (lease != null) owned.remove(executionId, lease);
            // An execution/close error can happen after PostgreSQL acquired the session lock.
            // A logical pooled close alone could return that still-locked session to another caller.
            discard(connection, failure);
            throw failure;
        }
    }

    private void discard(Connection connection, Throwable failure) {
        try { connection.abort(Runnable::run); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        try { connection.close(); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }

    @Override
    public void assertOwned(UUID executionId) {
        Lease lease = owned.get(executionId);
        if (lease == null) throw new OwnershipLostException("Execution has no local lease: " + executionId);
        lease.assertHeld();
    }

    public final class Lease implements AutoCloseable {
        private final Connection connection;
        private final long key;
        private final UUID executionId;
        private final int backendPid;
        private final Timestamp backendStartedAt;
        private volatile boolean lost;

        private Lease(Connection connection, long key, UUID executionId, int backendPid,
                Timestamp backendStartedAt) {
            this.connection = connection;
            this.key = key;
            this.executionId = executionId;
            this.backendPid = backendPid;
            this.backendStartedAt = backendStartedAt;
        }

        /** Validate the original session, not merely a replacement connection from the pool. */
        public synchronized void assertHeld() {
            if (lost) throw new OwnershipLostException("Execution lease was lost: " + executionId);
            SQLException failure = null;
            // A cancelled two-second SELECT does not release a PostgreSQL session lock. Retry that
            // same session before treating a brief database delay as an interrupted execution.
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    confirm(observe(connection));
                    return;
                } catch (SQLException e) {
                    if (failure == null) failure = e;
                    else if (failure != e) failure.addSuppressed(e);
                }
                if (Thread.currentThread().isInterrupted()) break;
                if (attempt < 2) java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
            }
            // Pin both PID and backend_start: PostgreSQL may eventually reuse a departed PID.
            Connection observer = null;
            boolean observedHeld;
            try {
                observer = dataSource.getConnection();
                observedHeld = observe(observer);
            } catch (SQLException observationFailure) {
                if (observer != null) discard(observer, observationFailure);
                if (failure != null && failure != observationFailure) observationFailure.addSuppressed(failure);
                // Unknown is intentionally not sticky lost=true. The runner stops new effects and
                // keeps delivery pending, but this result is not proof that the lock disappeared.
                throw new OwnershipUncertainException("Cannot establish execution ownership: " + executionId,
                    observationFailure);
            }
            try { observer.close(); }
            catch (SQLException cleanupFailure) {
                // A completed observation remains evidence even if returning the observer to its
                // pool fails. Dispose that unrelated session without cancelling a healthy workflow.
                discard(observer, cleanupFailure);
                log.warn("Cannot close execution ownership observer: {}", executionId, cleanupFailure);
            }
            confirm(observedHeld);
        }

        private void confirm(boolean held) {
            if (!held) {
                lost = true;
                throw new OwnershipLostException("Original execution lease no longer exists: " + executionId);
            }
        }

        private boolean observe(Connection session) throws SQLException {
            try (var statement = session.prepareStatement("select exists (select 1 from pg_locks l "
                    + "join pg_stat_activity a on a.pid = l.pid where l.locktype = 'advisory' "
                    + "and l.pid = ? and a.backend_start = ? and l.granted "
                    + "and l.classid::bigint = ? and l.objid::bigint = ? and l.objsubid = 1)")) {
                statement.setQueryTimeout(2);
                statement.setInt(1, backendPid);
                statement.setTimestamp(2, backendStartedAt);
                statement.setLong(3, (key >>> 32) & 0xffff_ffffL);
                statement.setLong(4, key & 0xffff_ffffL);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Empty ownership observation");
                    return result.getBoolean(1);
                }
            }
        }

        @Override
        public synchronized void close() throws SQLException {
            boolean discarded = false;
            try (var statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, key);
                statement.execute();
            } catch (SQLException | RuntimeException | Error failure) {
                // A pooled connection must never retain a lock after a failed unlock.
                discarded = true;
                discard(connection, failure);
                throw failure;
            } finally {
                lost = true;
                owned.remove(executionId, this);
                if (!discarded) {
                    try { connection.close(); }
                    catch (SQLException | RuntimeException | Error failure) {
                        discard(connection, failure);
                        throw failure;
                    }
                }
            }
        }
    }
}
