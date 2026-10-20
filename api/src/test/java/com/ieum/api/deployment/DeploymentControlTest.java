package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

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
    }
}
