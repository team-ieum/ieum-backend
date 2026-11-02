package com.ieum.api.deployment;

import com.ieum.workflowcore.deployment.BackgroundOwnership;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentControl implements BackgroundOwnership {
    private final StringRedisTemplate redis;
    private final String instance;
    private final Path directory;
    private final String consumerName;
    private volatile boolean draining;

    public DeploymentControl(StringRedisTemplate redis,
            @Value("${ieum.deployment.instance}") String instance,
            @Value("${ieum.deployment.control-dir:/deployment}") String directory) {
        this.redis = redis;
        this.instance = instance;
        this.directory = Path.of(directory);
        this.consumerName = instance + "-" + UUID.randomUUID();
    }

    public boolean distributedReady() {
        try { return Files.readString(directory.resolve("phase")).strip().equals("ready"); }
        catch (Exception ignored) { return false; }
    }

    @Override
    public boolean isOwner() {
        try {
            return !draining && distributedReady()
                && Files.readString(directory.resolve("active")).strip().equals(instance);
        } catch (Exception ignored) { return false; }
    }

    public void heartbeat() {
        redis.opsForValue().set("ieum:deployment:alive:" + consumerName,
            draining ? "draining" : "1", Duration.ofSeconds(90));
    }

    /** Consumer liveness belongs to a process, never to a reusable blue/green slot. */
    public String consumerName() { return consumerName; }
    public boolean isDraining() { return draining; }
    public void drain() { draining = true; }
    public void resume() { draining = false; }

    public boolean consumerAlive(String consumer) {
        // A drained process may retain unread PEL entries after its last Redis read batch.
        // They must be transferable; any still-running execution remains protected by its DB lease.
        return "1".equals(redis.opsForValue().get("ieum:deployment:alive:" + consumer));
    }
}
