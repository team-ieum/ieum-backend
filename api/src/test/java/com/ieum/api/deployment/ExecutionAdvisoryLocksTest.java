package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExecutionAdvisoryLocksTest {
    @ParameterizedTest
    @ValueSource(strings = {"statement", "result"})
    void resourceCloseFailureDiscardsSessionWithoutLeavingAnUnreachableLease(String resource) throws Exception {
        var source = mock(DataSource.class);
        var broken = mock(Connection.class);
        var next = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(broken, next);
        when(broken.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true); // PostgreSQL acquired the lock before close failed.
        var failure = new SQLException(resource + " close failed");
        if (resource.equals("statement")) doThrow(failure).when(statement).close();
        else doThrow(failure).when(result).close();
        var nextStatement = mock(PreparedStatement.class);
        var nextResult = mock(ResultSet.class);
        when(next.prepareStatement(anyString())).thenReturn(nextStatement);
        when(nextStatement.executeQuery()).thenReturn(nextResult);
        when(nextResult.next()).thenReturn(true);
        when(nextResult.getBoolean(1)).thenReturn(true);
        var locks = new ExecutionAdvisoryLocks(source);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> locks.tryAcquire(id)).isSameAs(failure);
        verify(broken).abort(any(Executor.class));
        verify(broken).close();
        // The failed attempt must not leave owned[id], otherwise this incorrectly returns null.
        try (var recovered = locks.tryAcquire(id)) { assertThat(recovered).isNotNull(); }
    }

    @Test
    void ambiguousAcquireFailurePreservesOriginalErrorAndDiscardsPossiblyLockedSession() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        var original = new SQLException("query timeout after server accepted lock");
        var abort = new SQLException("abort failed");
        var close = new SQLException("close failed");
        when(statement.executeQuery()).thenThrow(original);
        doThrow(abort).when(connection).abort(any(Executor.class));
        doThrow(close).when(connection).close();
        var locks = new ExecutionAdvisoryLocks(source);
        assertThatThrownBy(() -> locks.tryAcquire(UUID.randomUUID())).isSameAs(original)
            .satisfies(failure -> assertThat(failure.getSuppressed()).contains(abort, close));
        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    void failedUnlockPreservesOriginalErrorAndReleasesLocalOwnership() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        var locks = new ExecutionAdvisoryLocks(source);
        UUID id = UUID.randomUUID();
        var lease = locks.tryAcquire(id);
        var original = new SQLException("unlock failed");
        when(statement.execute()).thenThrow(original);
        doThrow(new SQLException("abort failed")).when(connection).abort(any(Executor.class));
        doThrow(new SQLException("close failed")).when(connection).close();
        assertThatThrownBy(lease::close).isSameAs(original);
        verify(connection).abort(any(Executor.class));
        // Local ownership is removed even when both physical connection cleanup operations fail.
        when(source.getConnection()).thenThrow(new SQLException("fresh connection requested"));
        assertThatThrownBy(() -> locks.tryAcquire(id)).hasMessage("fresh connection requested");
    }
}
