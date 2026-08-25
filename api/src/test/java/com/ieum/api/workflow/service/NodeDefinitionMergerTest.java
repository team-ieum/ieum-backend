package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.NodeDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 부분 수정 요청이 이전 정의의 값을 지우지 않는지 검증한다 (IEUM-BE-65).
 *
 * <p>요청 DTO에 세터가 없으므로 실제 요청 본문과 같은 방식(JSON 역직렬화)으로 노드를 만든다.
 */
class NodeDefinitionMergerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 세 필드를 각각 단언하던 테스트 셋을 하나로 합쳤다 — 전부 같은 사실(값이 null인 필드는 이전
     * 값을 덮지 않는다)을 확인했고, 셋을 한 번에 생략한 이 요청이 그 상위 집합이다.
     */
    @Test
    @DisplayName("description·position·config를 보내지 않으면 이전 값이 모두 보존된다")
    void 보내지_않은_필드는_이전_값을_유지한다() {
        List<Map<String, Object>> previous = List.of(previousNode("n1", Map.of(
            "description", "이전 설명",
            "position", Map.of("x", 10.0, "y", 20.0),
            "config", Map.of("prompt", "이전 프롬프트"))));

        List<Map<String, Object>> merged = NodeDefinitionMerger.merge(previous,
            List.of(node("""
                {"id":"n1","type":"AI","label":"라벨"}
                """)), objectMapper);

        assertThat(merged).singleElement().satisfies(n -> assertThat(n)
            .containsEntry("description", "이전 설명")
            .containsEntry("position", Map.of("x", 10.0, "y", 20.0))
            .containsEntry("config", Map.of("prompt", "이전 프롬프트")));
    }

    @Test
    @DisplayName("보낸 필드는 새 값으로 교체된다")
    void 보낸_필드_교체() {
        List<Map<String, Object>> previous = List.of(previousNode("n1",
            Map.of("label", "이전 라벨", "type", "HTTP", "config", Map.of("url", "이전"))));

        List<Map<String, Object>> merged = NodeDefinitionMerger.merge(previous,
            List.of(node("""
                {"id":"n1","type":"AI","label":"새 라벨","config":{"prompt":"새 프롬프트"}}
                """)), objectMapper);

        assertThat(merged.get(0)).containsEntry("label", "새 라벨")
            .containsEntry("type", "AI")
            .containsEntry("config", Map.of("prompt", "새 프롬프트"));
    }

    @Test
    @DisplayName("요청에 없는 이전 노드는 결과에서 삭제된다")
    void 요청에_없는_노드_삭제() {
        List<Map<String, Object>> previous = List.of(
            previousNode("n1", Map.of("label", "남길 노드")),
            previousNode("n2", Map.of("label", "지울 노드")));

        List<Map<String, Object>> merged = NodeDefinitionMerger.merge(previous,
            List.of(node("""
                {"id":"n1","type":"AI","label":"남길 노드"}
                """)), objectMapper);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0)).containsEntry("id", "n1");
    }

    @Test
    @DisplayName("요청에만 있는 새 노드는 position 없이 그대로 저장된다")
    void 새_노드는_좌표를_서버가_채우지_않는다() {
        List<Map<String, Object>> merged = NodeDefinitionMerger.merge(
            List.of(previousNode("n1", Map.of("position", Map.of("x", 1.0, "y", 2.0)))),
            List.of(node("""
                {"id":"n2","type":"AI","label":"새 노드"}
                """)), objectMapper);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0)).containsEntry("id", "n2").doesNotContainKey("position");
    }

    @Test
    @DisplayName("이전 노드에만 있던 알 수 없는 키도 보존된다")
    void 알_수_없는_키_보존() {
        List<Map<String, Object>> previous = List.of(
            previousNode("n1", Map.of("_legacyFlag", "keep-me")));

        List<Map<String, Object>> merged = NodeDefinitionMerger.merge(previous,
            List.of(node("""
                {"id":"n1","type":"AI","label":"라벨"}
                """)), objectMapper);

        assertThat(merged.get(0)).containsEntry("_legacyFlag", "keep-me");
    }

    @Test
    @DisplayName("이전 정의가 null이거나 id가 깨져 있어도 예외 없이 요청 노드만 반환한다")
    void 깨진_이전_정의는_무시된다() {
        List<NodeDto> request = List.of(node("""
            {"id":"n1","type":"AI","label":"라벨"}
            """));

        assertThat(NodeDefinitionMerger.merge(null, request, objectMapper))
            .singleElement().satisfies(n -> assertThat(n).containsEntry("id", "n1")
                .doesNotContainKey("description"));

        List<Map<String, Object>> brokenPrevious = new ArrayList<>(Arrays.asList(
            new LinkedHashMap<>(Map.of("label", "id 없음", "description", "무시될 설명")),
            new LinkedHashMap<>(Map.of("id", 42, "description", "id가 문자열이 아님")),
            null));

        assertThat(NodeDefinitionMerger.merge(brokenPrevious, request, objectMapper))
            .singleElement().satisfies(n -> assertThat(n).containsEntry("id", "n1")
                .doesNotContainKey("description"));
    }


    @Test
    @DisplayName("결과 Map을 수정해도 이전 정의 원본은 바뀌지 않는다")
    void 결과는_이전_정의의_복사본이다() {
        Map<String, Object> previousNode = previousNode("n1", Map.of("description", "이전 설명"));
        List<Map<String, Object>> previous = List.of(previousNode);

        List<Map<String, Object>> merged = NodeDefinitionMerger.merge(previous,
            List.of(node("""
                {"id":"n1","type":"AI","label":"라벨"}
                """)), objectMapper);

        merged.get(0).put("description", "수정됨");
        merged.get(0).remove("id");

        assertThat(previousNode).containsEntry("description", "이전 설명")
            .containsEntry("id", "n1");
    }

    private Map<String, Object> previousNode(String id, Map<String, Object> fields) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.putAll(fields);
        return node;
    }

    private NodeDto node(String json) {
        try {
            return objectMapper.readValue(json, NodeDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("테스트 픽스처 파싱 실패", e);
        }
    }
}
