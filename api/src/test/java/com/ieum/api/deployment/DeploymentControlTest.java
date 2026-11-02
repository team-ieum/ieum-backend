package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;

class DeploymentControlTest {
    @TempDir Path directory;
    @Test
    void bootstrapFailsClosedAndOnlyActiveInstanceOwnsMaintenance() throws Exception {
        var green = new DeploymentControl(mock(StringRedisTemplate.class), "green", directory.toString());
        var blue = new DeploymentControl(mock(StringRedisTemplate.class), "blue", directory.toString());
        assertThat(blue.distributedReady()).isFalse();
        Files.writeString(directory.resolve("phase"), "legacy");
        Files.writeString(directory.resolve("active"), "blue");
        assertThat(blue.isOwner()).isFalse();
        Files.writeString(directory.resolve("phase"), "ready");
        assertThat(blue.isOwner()).isTrue();
        assertThat(green.isOwner()).isFalse();
        Files.writeString(directory.resolve("active"), "green");
        assertThat(blue.isOwner()).isFalse();
        assertThat(green.isOwner()).isTrue();
        green.drain();
        assertThat(green.isOwner()).isFalse();
        green.resume();
        assertThat(green.isOwner()).isTrue();
    }

    @Test
    void restartingSameSlotHasDifferentConsumerIdentity() {
        var first = new DeploymentControl(mock(StringRedisTemplate.class), "blue", directory.toString());
        var restarted = new DeploymentControl(mock(StringRedisTemplate.class), "blue", directory.toString());
        assertThat(first.consumerName()).startsWith("blue-").isNotEqualTo(restarted.consumerName());
    }

    @Test
    void drainedConsumerHeartbeatAllowsTransferOfUnstartedPending() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var control = new DeploymentControl(redis, "blue", directory.toString());
        String key = "ieum:deployment:alive:" + control.consumerName();
        control.heartbeat();
        verify(values).set(key, "1", Duration.ofSeconds(90));
        control.drain();
        control.heartbeat();
        verify(values).set(key, "draining", Duration.ofSeconds(90));
        when(values.get(key)).thenReturn("draining");
        assertThat(control.consumerAlive(control.consumerName())).isFalse();
    }
}
