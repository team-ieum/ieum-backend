package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.engine.executor.ApprovalGateConfig;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalGateRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID executionId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 9, 10, 0);
    /** 인메모리 대역 — save는 쌓고 deleteByExecutionId는 그 실행 행을 지운다. */
    private final List<ApprovalGate> rows = new ArrayList<>();
    private ApprovalGateRecorder recorder;

    @BeforeEach
    void setUp() {
        ApprovalGateRepository repository = mock(ApprovalGateRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        doAnswer(inv -> rows.removeIf(g -> g.getExecutionId().equals(inv.getArgument(0))))
            .when(repository).deleteByExecutionId(any());
        recorder = new ApprovalGateRecorder(repository, objectMapper);
    }

    private ApprovalGateConfig request(List<Map<String, Object>> items) {
        return ApprovalGateConfig.parse(ApprovalGateConfig.REQUEST, Map.of("items", items));
    }

    private Map<String, Object> view(ApprovalGate gate) throws Exception {
        return objectMapper.readValue(gate.getViewJson(), new TypeReference<>() {});
    }

    @Test
    @DisplayName("렌더 결과는 item마다 마스킹하고 상한에서 자른다")
    void 렌더_결과는_마스킹하고_자른다() throws Exception {
        ApprovalGateConfig config = request(List.of(
            Map.of("key", "apiKey", "value", "sk-1"),
            Map.of("key", "hook", "value", "https://discord.com/api/webhooks/1/abc"),
            Map.of("key", "big", "value", "x".repeat(12_000))));

        recorder.record(executionId, workflowId, userId, Map.of("g", config), now);

        assertThat(rows).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) view(rows.get(0)).get("items");
        assertThat(items.get(0).get("value")).isEqualTo("***");
        assertThat((String) items.get(1).get("value")).doesNotContain("abc").contains("discord.com/api/webhooks/");
        assertThat((String) items.get(2).get("value")).hasSize(10_001);
    }

    @Test
    @DisplayName("재배달은 그 실행의 이전 게이트 행을 지우고 이번 게이트만 남긴다")
    void 재배달은_이번_게이트만_남긴다() throws Exception {
        Map<String, ApprovalGateConfig> first = new LinkedHashMap<>();
        first.put("A", request(List.of(Map.of("key", "v", "value", "첫째"))));
        first.put("B", request(List.of(Map.of("key", "v", "value", "첫째"))));
        recorder.record(executionId, workflowId, userId, first, now);

        recorder.record(executionId, workflowId, userId,
            Map.of("A", request(List.of(Map.of("key", "v", "value", "둘째")))), now);

        assertThat(rows).extracting(ApprovalGate::getNodeId).containsExactly("A");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) view(rows.get(0)).get("items");
        assertThat(items.get(0).get("value")).isEqualTo("둘째");
    }
}
