package com.ieum.api.deployment;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Rejects newly built standalone instances once this Redis namespace enters distributed mode.
 * Older images do not contain this guard: disabling their deployment workflows is still required.
 * The persistent marker must only be removed as part of an explicit, fully stopped migration.
 */
@Component
@ConditionalOnProperty(name = "ieum.deployment.mode-guard.enabled", havingValue = "true", matchIfMissing = true)
public class DeploymentModeGuard implements SmartInitializingSingleton {
    public static final String MODE_KEY = "ieum:deployment:mode";
    private final StringRedisTemplate redis;
    private final boolean distributed;

    public DeploymentModeGuard(StringRedisTemplate redis,
            @Value("${ieum.deployment.distributed.enabled:false}") boolean distributed) {
        this.redis = redis;
        this.distributed = distributed;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Runs before SmartLifecycle auto-start and ApplicationRunner queue/schedule restoration.
        if (distributed) {
            redis.opsForValue().set(MODE_KEY, "distributed-v1");
        } else if (!Boolean.FALSE.equals(redis.hasKey(MODE_KEY))) {
            throw new IllegalStateException("Distributed namespace is present or unverified; refusing legacy startup");
        }
        // Redis lookup failure propagates: absence of a mode marker must be proven, not guessed.
    }
}
