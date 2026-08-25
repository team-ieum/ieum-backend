package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.EdgeDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 부분 수정 요청이 엣지의 {@code conditionType}을 지우지 않는지 검증한다 (IEUM-BE-65).
 *
 * <p>요청 DTO에 세터가 없으므로 실제 요청 본문과 같은 방식(JSON 역직렬화)으로 엣지를 만든다.
 */
class EdgeDefinitionMergerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("conditionType을 보내지 않으면 이전 분기 값이 보존된다")
    void 보내지_않은_conditionType은_이전_값을_유지한다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("cond", "n-yes", "true"),
            previousEdge("cond", "n-no", "false"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"source":"cond","target":"n-yes"},
             {"source":"cond","target":"n-no"}]
            """));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("false");
    }

    /**
     * 요청이 값을 보냈을 때도 큐를 하나 소비해야 한다 — 소비하지 않으면 같은 키의 두 번째 요청
     * 엣지가 이미 짝지어진 첫 번째 이전 엣지("false")를 다시 집는다.
     */
    @Test
    @DisplayName("conditionType을 보내면 새 값이 이기고, 이전 엣지 큐도 한 칸 소비된다")
    void 보낸_conditionType이_이긴다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("cond", "merge", "false"),
            previousEdge("cond", "merge", "true"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"source":"cond","target":"merge","conditionType":"true"},
             {"source":"cond","target":"merge"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        // 두 번째는 큐의 두 번째 값을 받는다 — 첫 번째("false")를 다시 집으면 안 된다.
        assertThat(merged.get(1).get("conditionType")).isEqualTo("true");
    }

    @Test
    @DisplayName("같은 (source,target)에 분기 두 개가 있으면 등장 순서대로 각각 보존된다")
    void 같은_키의_두_분기는_순서대로_짝지어진다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("cond", "merge", "true"),
            previousEdge("cond", "merge", "false"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"source":"cond","target":"merge"},
             {"source":"cond","target":"merge"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("false");
    }

    @Test
    @DisplayName("요청에만 있는 새 엣지는 conditionType 없이 그대로 저장된다")
    void 요청에만_있는_엣지는_그대로_저장된다() {
        List<Map<String, Object>> previous = List.of(previousEdge("cond", "n-yes", "true"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"source":"n-yes","target":"n-end"}]
            """));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0)).containsOnlyKeys("source", "target");
        assertThat(merged.get(0).get("source")).isEqualTo("n-yes");
        assertThat(merged.get(0).get("target")).isEqualTo("n-end");
    }

    /**
     * 저장된 정의의 구조는 BE가 통제하지 못한다 — 어떤 문서가 와도 예외로 PUT을 500으로 만들면
     * 안 된다. 해석할 수 없는 이전 엣지는 조용히 무시하고 요청 값만 저장한다.
     */
    @Test
    @DisplayName("이전 엣지가 없거나 해석할 수 없어도 예외 없이 요청 엣지를 저장한다")
    void 해석할_수_없는_이전_엣지는_무시한다() {
        List<EdgeDto> request = edges("""
            [{"source":"a","target":"b"}]
            """);

        assertThat(EdgeDefinitionMerger.merge(null, request).get(0))
            .containsOnlyKeys("source", "target");
        assertThat(EdgeDefinitionMerger.merge(List.of(), request).get(0))
            .containsOnlyKeys("source", "target");

        // 원소가 Map이 아니거나(제네릭이 지워진 자리), null이거나, source/target이 문자열이 아닌 문서.
        List<Map<String, Object>> broken = rawList(
            "엣지가 아닌 문자열",
            null,
            Map.of("source", 1, "target", 2, "conditionType", "true"),
            previousEdge("a", "b", "true"));

        assertThatCode(() -> EdgeDefinitionMerger.merge(broken, request)).doesNotThrowAnyException();
        // 성한 엣지 하나는 그래도 짝지어진다.
        assertThat(EdgeDefinitionMerger.merge(broken, request).get(0).get("conditionType"))
            .isEqualTo("true");
    }

    /** conditionType이 null인 이전 엣지도 큐를 차지한다 — 소비 순서가 어긋나면 분기가 뒤바뀐다. */
    @Test
    @DisplayName("conditionType이 없는 이전 엣지도 큐 자리를 차지한다")
    void conditionType이_null인_이전_엣지도_큐를_소비한다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("a", "b", null),
            previousEdge("a", "b", "true"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"source":"a","target":"b"},
             {"source":"a","target":"b"}]
            """));

        assertThat(merged.get(0)).containsOnlyKeys("source", "target");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("true");
    }

    private Map<String, Object> previousEdge(String source, String target, String conditionType) {
        // null 값을 담아야 해서 Map.of를 쓸 수 없다.
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        edge.put("conditionType", conditionType);
        return edge;
    }

    /** null·비-Map 원소를 섞기 위한 리스트. 저장된 정의에서 실제로 올 수 있는 모양이다. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> rawList(Object... items) {
        return new ArrayList(Arrays.asList(items));
    }

    private List<EdgeDto> edges(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, EdgeDto.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
