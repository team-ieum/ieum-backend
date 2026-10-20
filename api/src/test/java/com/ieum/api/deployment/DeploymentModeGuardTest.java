package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentModeGuardTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void distributedModePersistsMarker() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        new DeploymentModeGuard(redis, true).afterSingletonsInstantiated();
        verify(values).set(DeploymentModeGuard.MODE_KEY, "distributed-v1");
    }

    @Test
    void legacyModeRefusesDistributedNamespace() {
        when(redis.hasKey(DeploymentModeGuard.MODE_KEY)).thenReturn(true);
        assertThatIllegalStateException().isThrownBy(() ->
            new DeploymentModeGuard(redis, false).afterSingletonsInstantiated());
    }

    @Test
    void redisFailureDoesNotAllowUnverifiedLegacyStartup() {
        when(redis.hasKey(DeploymentModeGuard.MODE_KEY))
            .thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> new DeploymentModeGuard(redis, false).afterSingletonsInstantiated())
            .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void standaloneNamespaceCanStillStart() {
        when(redis.hasKey(DeploymentModeGuard.MODE_KEY)).thenReturn(false);
        assertThatCode(() -> new DeploymentModeGuard(redis, false).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
    }

    @Test
    void indeterminateRedisResultDoesNotProveStandaloneNamespace() {
        when(redis.hasKey(DeploymentModeGuard.MODE_KEY)).thenReturn(null);
        assertThatIllegalStateException().isThrownBy(() ->
            new DeploymentModeGuard(redis, false).afterSingletonsInstantiated());
    }

    @Test
    void defaultSpringWiringRefusesLegacyStartupBeforeApplicationRunners() {
        when(redis.hasKey(DeploymentModeGuard.MODE_KEY)).thenReturn(true);
        new ApplicationContextRunner().withUserConfiguration(DeploymentModeGuard.class)
            .withBean(StringRedisTemplate.class, () -> redis)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void noRedisTestFixtureOptOutIsExplicitAndDoesNotReadNamespace() {
        new ApplicationContextRunner().withUserConfiguration(DeploymentModeGuard.class)
            .withBean(StringRedisTemplate.class, () -> redis)
            .withPropertyValues("ieum.deployment.mode-guard.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(DeploymentModeGuard.class));
        verify(redis, never()).hasKey(anyString());
        verify(redis, never()).opsForValue();
    }
}
