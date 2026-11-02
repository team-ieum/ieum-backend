package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.engine.ExecutionOwnershipGuard.OwnershipLostException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "IEUM_TEST_POSTGRES_URL", matches = ".+")
class ExecutionAdvisoryLocksPostgresIntegrationTest {
    @Test
    void originalSessionTerminationRevokesGuardAndAllowsAnotherOwner() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("IEUM_TEST_POSTGRES_URL"),
            System.getenv("IEUM_TEST_POSTGRES_USER"), System.getenv("IEUM_TEST_POSTGRES_PASSWORD"));
        UUID id = UUID.randomUUID();
        long key = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        var blue = new ExecutionAdvisoryLocks(source);
        var lease = blue.tryAcquire(id);
        assertThat(lease).isNotNull();
        try {
            blue.assertOwned(id);
            try (var connection = source.getConnection();
                    var terminate = connection.prepareStatement("select pg_terminate_backend(pid) from pg_locks "
                        + "where locktype = 'advisory' and classid::bigint = ? "
                        + "and objid::bigint = ? and objsubid = 1 and pid <> pg_backend_pid()")) {
                terminate.setLong(1, (key >>> 32) & 0xffff_ffffL);
                terminate.setLong(2, key & 0xffff_ffffL);
                try (var result = terminate.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            }
            assertThatThrownBy(() -> blue.assertOwned(id)).isInstanceOf(OwnershipLostException.class);
            var green = new ExecutionAdvisoryLocks(source);
            try (var replacement = green.tryAcquire(id)) {
                assertThat(replacement).isNotNull();
                green.assertOwned(id);
            }
        } finally {
            try { lease.close(); }
            catch (java.sql.SQLException expectedAfterSessionTermination) { /* Already revoked. */ }
        }
    }
}
