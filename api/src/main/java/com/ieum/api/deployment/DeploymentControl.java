package com.ieum.api.deployment;

import com.ieum.workflowcore.deployment.BackgroundOwnership;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    public DeploymentControl(StringRedisTemplate redis,
            @Value("${ieum.deployment.instance}") String instance,
            @Value("${ieum.deployment.control-dir:/deployment}") String directory) {
        this.redis = redis;
        this.instance = instance;
        this.directory = Path.of(directory);
    }

    public boolean distributedReady() {
        try { return Files.readString(directory.resolve("phase")).strip().equals("ready"); }
        catch (Exception ignored) { return false; }
    }

    @Override
    public boolean isOwner() {
        try {
            return distributedReady()
                && Files.readString(directory.resolve("active")).strip().equals(instance);
        } catch (Exception ignored) { return false; }
    }

    public void heartbeat() {
        redis.opsForValue().set("ieum:deployment:alive:" + instance, "1", Duration.ofSeconds(90));
    }

    public boolean consumerAlive(String consumer) {
        return Boolean.TRUE.equals(redis.hasKey("ieum:deployment:alive:" + consumer));
    }
}
