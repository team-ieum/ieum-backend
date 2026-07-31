package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    @DisplayName("같은 executionId·nodeId면 항상 같은 키를 반환한다 (재시도 전 회차 동일 키)")
    void sameInputProducesSameKey() {
        String key1 = IdempotencyKeys.generate("exec-1", "node-1");
        String key2 = IdempotencyKeys.generate("exec-1", "node-1");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("executionId가 다르면 키가 다르다")
    void differentExecutionIdProducesDifferentKey() {
        String key1 = IdempotencyKeys.generate("exec-1", "node-1");
        String key2 = IdempotencyKeys.generate("exec-2", "node-1");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("nodeId가 다르면 키가 다르다")
    void differentNodeIdProducesDifferentKey() {
        String key1 = IdempotencyKeys.generate("exec-1", "node-1");
        String key2 = IdempotencyKeys.generate("exec-1", "node-2");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("sha256의 앞 32자 hex를 반환한다")
    void returnsFirst32HexChars() {
        String key = IdempotencyKeys.generate("exec-1", "node-1");

        assertThat(key).hasSize(32);
        assertThat(key).matches("[0-9a-f]{32}");
    }
}
