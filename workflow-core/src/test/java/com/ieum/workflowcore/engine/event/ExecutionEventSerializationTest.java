package com.ieum.workflowcore.engine.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSE로 나가는 {@link ExecutionEvent}의 JSON 규격을 고정하는 테스트.
 *
 * <p>프론트 실행 화면이 이미 읽고 있는 키·값이라 여기서 깨지면 곧 화면이 깨진다.
 */
@DisplayName("ExecutionEvent SSE 직렬화 규격")
class ExecutionEventSerializationTest {

    /** api 모듈의 SSE 직렬화는 Spring Boot가 구성한 ObjectMapper를 쓴다 — 그 구성에 맞춘다. */
    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .build();

    private final UUID executionId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private JsonNode serialize(ExecutionEvent event) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(event));
    }

    @Test
    @DisplayName("이벤트 종류 키는 type이다 — eventType으로 바뀌면 프론트가 깨진다")
    void event_kind_key_is_type() throws Exception {
        JsonNode json = serialize(
            ExecutionEvent.nodeStarted(executionId, workflowId, "node-1", NodeType.AI));

        assertThat(json.hasNonNull("type")).isTrue();
        assertThat(json.get("type").asText()).isEqualTo("NODE_STARTED");
        assertThat(json.has("eventType")).isFalse();
    }

    @Test
    @DisplayName("모든 이벤트가 executionId·workflowId·occurredAt(ISO-8601 문자열)을 싣는다")
    void every_event_carries_envelope_fields() throws Exception {
        var events = java.util.List.of(
            ExecutionEvent.nodeStarted(executionId, workflowId, "node-1", NodeType.AI),
            ExecutionEvent.nodeCompleted(executionId, workflowId, "node-1", NodeType.AI, 12L),
            ExecutionEvent.nodeFailed(executionId, workflowId, "node-1", NodeType.AI, "실패", 12L),
            ExecutionEvent.executionCompleted(executionId, workflowId, ExecutionStatus.SUCCESS));

        for (ExecutionEvent event : events) {
            JsonNode json = serialize(event);
            assertThat(json.get("executionId").asText()).isEqualTo(executionId.toString());
            assertThat(json.get("workflowId").asText()).isEqualTo(workflowId.toString());
            // 숫자 타임스탬프가 아니라 문자열이어야 하고, ISO-8601로 파싱돼야 한다.
            JsonNode occurredAt = json.get("occurredAt");
            assertThat(occurredAt.isTextual()).isTrue();
            assertThatCode(() -> Instant.parse(occurredAt.asText())).doesNotThrowAnyException();
            assertThat(Instant.parse(occurredAt.asText())).isEqualTo(event.occurredAt());
        }
    }

    @Test
    @DisplayName("NODE_STARTED는 status RUNNING을 싣는다")
    void node_started_carries_running_status() throws Exception {
        JsonNode json = serialize(
            ExecutionEvent.nodeStarted(executionId, workflowId, "node-1", NodeType.AI));

        assertThat(json.get("status").asText()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("성공·실패 이벤트의 status 문자열은 기존 값(SUCCESS·FAILED) 그대로다")
    void node_result_status_strings_unchanged() throws Exception {
        JsonNode completed = serialize(
            ExecutionEvent.nodeCompleted(executionId, workflowId, "node-1", NodeType.AI, 12L));
        JsonNode failed = serialize(
            ExecutionEvent.nodeFailed(executionId, workflowId, "node-1", NodeType.AI, "실패", 12L));

        assertThat(completed.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("스킵 이벤트는 type NODE_COMPLETED + status SKIPPED다 — 프론트가 모르는 type을 만들지 않는다")
    void node_skipped_reuses_completed_type() throws Exception {
        JsonNode json = serialize(
            ExecutionEvent.nodeSkipped(executionId, workflowId, "node-1", NodeType.AI, 0L));

        assertThat(json.get("type").asText()).isEqualTo("NODE_COMPLETED");
        assertThat(json.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(json.get("nodeId").asText()).isEqualTo("node-1");
        assertThat(json.has("errorMessage")).isFalse();
    }

    @Test
    @DisplayName("ExecutionLogStatus와 이름이 겹치는 값은 직렬화 문자열이 서로 같다")
    void event_status_strings_match_persisted_status_strings() throws Exception {
        for (ExecutionLogStatus persisted : ExecutionLogStatus.values()) {
            NodeEventStatus event = NodeEventStatus.valueOf(persisted.name());
            assertThat(mapper.writeValueAsString(event))
                .isEqualTo(mapper.writeValueAsString(persisted));
        }
    }

    @Test
    @DisplayName("영속용 ExecutionLogStatus 값은 늘어나지 않았다 — node_runs.status 의미 보존")
    void persisted_status_enum_values_unchanged() {
        assertThat(Arrays.stream(ExecutionLogStatus.values()).map(Enum::name))
            .containsExactly("SUCCESS", "FAILED", "SKIPPED");
    }

    @Test
    @DisplayName("SSE 전용 status enum은 PENDING·RUNNING까지 가진다")
    void event_status_enum_values() {
        assertThat(Arrays.stream(NodeEventStatus.values()).map(Enum::name))
            .containsExactlyInAnyOrder("PENDING", "RUNNING", "SUCCESS", "FAILED", "SKIPPED");
    }

    @Test
    @DisplayName("null 필드는 직렬화에서 빠진다 — EXECUTION_COMPLETED에 노드 필드가 없다")
    void null_fields_are_omitted() throws Exception {
        JsonNode json = serialize(
            ExecutionEvent.executionCompleted(executionId, workflowId, ExecutionStatus.FAILED));

        assertThat(json.has("nodeId")).isFalse();
        assertThat(json.has("status")).isFalse();
        assertThat(json.get("executionStatus").asText()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("withOccurredAt은 발생 시각만 바꾼 복사본을 만든다 — 스냅샷 재생용")
    void with_occurred_at_replaces_only_timestamp() {
        ExecutionEvent original =
            ExecutionEvent.nodeCompleted(executionId, workflowId, "node-1", NodeType.AI, 12L);
        Instant recorded = Instant.parse("2026-01-02T03:04:05Z");

        ExecutionEvent replayed = original.withOccurredAt(recorded);

        assertThat(replayed.occurredAt()).isEqualTo(recorded);
        assertThat(replayed).isEqualTo(original.withOccurredAt(recorded));
        assertThat(replayed.withOccurredAt(original.occurredAt())).isEqualTo(original);
    }
}
