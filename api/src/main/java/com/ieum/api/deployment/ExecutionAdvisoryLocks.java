package com.ieum.api.deployment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.ieum.workflowcore.engine.ExecutionOwnershipGuard;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Session-scoped locks cannot expire while a healthy instance is still executing a job. */
@Component
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
            try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setLong(1, key);
                try (var result = statement.executeQuery()) {
                    acquired = result.next() && result.getBoolean(1);
                }
            }
            // Resource close can throw and cancel a return. Register only after both JDBC resources
            // have closed successfully, otherwise a caller that never received a lease cannot free it.
            if (acquired) {
                lease = new Lease(connection, key, executionId);
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
        private volatile boolean lost;

        private Lease(Connection connection, long key, UUID executionId) {
            this.connection = connection;
            this.key = key;
            this.executionId = executionId;
        }

        /** Validate the original session, not merely a replacement connection from the pool. */
        public synchronized void assertHeld() {
            if (lost) throw new OwnershipLostException("Execution lease was lost: " + executionId);
            try (var statement = connection.prepareStatement("select exists (select 1 from pg_locks "
                    + "where locktype = 'advisory' and pid = pg_backend_pid() and granted "
                    + "and classid::bigint = ? and objid::bigint = ? and objsubid = 1)")) {
                statement.setQueryTimeout(2);
                statement.setLong(1, (key >>> 32) & 0xffff_ffffL);
                statement.setLong(2, key & 0xffff_ffffL);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        lost = true;
                        throw new OwnershipLostException("Execution lease is no longer held: " + executionId);
                    }
                }
            } catch (SQLException e) {
                lost = true;
                throw new OwnershipLostException("Cannot validate execution lease: " + executionId, e);
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
