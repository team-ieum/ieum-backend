package com.ieum.api.deployment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Session-scoped locks cannot expire while a healthy instance is still executing a job. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class ExecutionAdvisoryLocks {
    private final DataSource dataSource;

    public Lease tryAcquire(UUID executionId) throws SQLException {
        long key = executionId.getMostSignificantBits() ^ executionId.getLeastSignificantBits();
        Connection connection = dataSource.getConnection();
        try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (var result = statement.executeQuery()) {
                result.next();
                if (result.getBoolean(1)) return new Lease(connection, key);
            }
            connection.close();
            return null;
        } catch (SQLException e) { connection.close(); throw e; }
    }

    public record Lease(Connection connection, long key) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            try (var statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, key);
                statement.execute();
            } catch (SQLException e) {
                // A pooled connection must never retain a lock after a failed unlock.
                connection.abort(Runnable::run);
                throw e;
            } finally { connection.close(); }
        }
    }
}
