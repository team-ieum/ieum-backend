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
     *
     * <p><b>이 테스트는 id 없는 폴백 경로의 계약이다.</b> 같은 키의 형제 엣지 중 하나에만
     * conditionType을 실으면 값이 중복되고 반대 분기가 사라지는데, 짝짓기 키가
     * {@code (source, target)}뿐이면 그 둘을 구분할 방법이 없다. 엣지에 id가 있으면 이 붕괴는
     * 일어나지 않는다(id가_있으면_형제_엣지가_붕괴하지_않는다). 레거시 정의와
     * ieum-agent가 만든 엣지에는 id가 없어 이 경로가 계속 살아 있어야 하므로, 여기 단언은
     * 그대로 둔다.
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

    // ------------------------------------------------------------------ id 우선 짝짓기

    @Test
    @DisplayName("id가 있으면 같은 id의 이전 엣지에서 conditionType을 잇는다")
    void id로_짝지어_conditionType을_잇는다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("e-yes", "cond", "n-yes", "true"),
            previousEdge("e-no", "cond", "n-no", "false"));

        // 요청 순서를 이전 정의와 반대로 보내도 각자 자기 id의 값을 가져온다.
        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"id":"e-no","source":"cond","target":"n-no"},
             {"id":"e-yes","source":"cond","target":"n-yes"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("false");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("true");
    }

    /**
     * 보낸_conditionType이_이긴다가 감수하던 붕괴 케이스가 id로 해소되는 것을 고정한다. 같은
     * {@code (cond, merge)} 형제라도 id가 다르므로 첫 엣지에만 conditionType을 실어도 둘째가
     * 자기 id의 {@code "false"}를 지킨다.
     */
    @Test
    @DisplayName("같은 (source,target) 형제라도 id가 있으면 각자 자기 분기 값을 지킨다")
    void id가_있으면_형제_엣지가_붕괴하지_않는다() {
        // 보낸_conditionType이_이긴다와 같은 배치다 — 이전 정의의 순서가 요청과 반대라 FIFO로는
        // 둘째가 "true"를 집어 분기가 붕괴한다.
        List<Map<String, Object>> previous = List.of(
            previousEdge("e-false", "cond", "merge", "false"),
            previousEdge("e-true", "cond", "merge", "true"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"id":"e-true","source":"cond","target":"merge","conditionType":"true"},
             {"id":"e-false","source":"cond","target":"merge"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("false");
    }

    @Test
    @DisplayName("엣지를 다른 노드로 옮겨 (source,target)이 바뀌어도 같은 id면 짝지어진다")
    void id는_엔드포인트가_바뀌어도_짝짓는다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("e-1", "cond", "n-old", "false"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"id":"e-1","source":"cond","target":"n-new"}]
            """));

        assertThat(merged.get(0).get("target")).isEqualTo("n-new");
        assertThat(merged.get(0).get("conditionType")).isEqualTo("false");
    }

    /** id로 집힌 이전 엣지가 FIFO 큐에 남아 있으면 id 없는 요청 엣지가 같은 값을 또 집는다. */
    @Test
    @DisplayName("id로 짝지어진 이전 엣지는 FIFO 큐에서도 빠진다")
    void id로_집힌_이전_엣지는_FIFO에서_제외된다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("e-1", "a", "b", "true"),
            previousEdge("e-2", "a", "b", "false"));

        // 앞의 요청 엣지는 id가 없어 FIFO로 가지만, e-1은 뒤쪽 요청 엣지가 id로 이미 가져갔다.
        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"source":"a","target":"b"},
             {"id":"e-1","source":"a","target":"b"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("false");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("true");
    }

    @Test
    @DisplayName("이전 정의에 없는 id는 새 엣지로 저장된다")
    void 요청에만_있는_id는_새_엣지다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("e-yes", "cond", "n-yes", "true"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"id":"e-yes","source":"cond","target":"n-yes"},
             {"id":"e-new","source":"n-yes","target":"n-end"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        assertThat(merged.get(1)).containsOnlyKeys("source", "target");
    }

    /**
     * 이전 정의에 id가 없는데 요청에만 id가 붙어 오는 모양(레거시 정의를 FE가 자기 id로 되돌려
     * 보내는 경우)에서 폴백이 살아 있어야 한다. id가 안 맞는다고 새 엣지로 처리하면 저장된
     * 분기 값이 매 저장마다 통째로 날아간다 — IEUM-BE-65가 막으려는 바로 그 증상이다.
     */
    @Test
    @DisplayName("이전 엣지에 id가 없으면 요청에 id가 있어도 기존 FIFO 결과가 그대로다")
    void 이전_엣지에_id가_없으면_FIFO로_폴백한다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("cond", "merge", "true"),
            previousEdge("cond", "merge", "false"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"id":"fe-1","source":"cond","target":"merge"},
             {"id":"fe-2","source":"cond","target":"merge"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("false");
    }

    /**
     * <b>의도된 트레이드오프의 기준선이다 — 버그가 아니다.</b> 이전·요청 양쪽에 id가 있어도 서로
     * 다르면 id로는 짝을 못 찾고 같은 {@code (source, target)}의 FIFO 폴백으로 내려간다. React Flow가
     * 엣지 id를 자체 생성하므로(서버가 준 id를 되돌려 보내지 않는 클라이언트가 있다) 이 폴백을
     * 막으면 conditionType이 통째로 사라진다.
     *
     * <p>나중에 "이전 엣지에 id가 있으면 FIFO 후보에서 제외"로 좁히면 이 테스트가 깨진다. 그때
     * 깨지는 것이 정상이니 값을 바꾸되, 무엇을 포기하는지 알고 바꿔라.
     */
    @Test
    @DisplayName("이전·요청 id가 서로 달라도 같은 (source,target)의 옛 conditionType을 FIFO로 잇는다")
    void 서로_다른_id는_FIFO로_폴백한다() {
        List<Map<String, Object>> previous = List.of(
            previousEdge("server-1", "cond", "merge", "true"),
            previousEdge("server-2", "cond", "merge", "false"));

        List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, edges("""
            [{"id":"rf-a","source":"cond","target":"merge"},
             {"id":"rf-b","source":"cond","target":"merge"}]
            """));

        assertThat(merged.get(0).get("conditionType")).isEqualTo("true");
        assertThat(merged.get(1).get("conditionType")).isEqualTo("false");
        // 짝지은 이전 엣지의 id도 실려 나온다 — 요청 id가 있으면 withEdgeIds가 이 값을 덮어쓴다.
        assertThat(merged).extracting(edge -> edge.get("id"))
            .containsExactly("server-1", "server-2");
    }

    /**
     * {@code WorkflowService.withEdgeIds}는 "요청 엣지 하나당 정확히 하나, 같은 순서"라는 성질에
     * 기대 <b>인덱스로</b> id를 붙인다. 병합이 엣지를 거르거나 재정렬하면 id가 엉뚱한 엣지에 붙고,
     * withEdgeIds의 길이 방어가 그 사고를 조용히 넘긴다(매 저장마다 새 UUID가 생겨 id 안정성이
     * 소리 없이 사라진다). 어떤 이전 정의가 와도 이 성질이 깨지지 않아야 한다.
     */
    @Test
    @DisplayName("병합 결과는 어떤 입력에서도 요청 엣지와 1:1이고 순서가 같다")
    void 병합_결과는_요청과_1대1_같은_순서다() {
        List<EdgeDto> request = edges("""
            [{"id":"e-1","source":"a","target":"b"},
             {"source":"a","target":"b"},
             {"id":"e-9","source":"x","target":"y-moved"},
             {"source":"c","target":"d","conditionType":"true"},
             {"id":"e-none","source":"e","target":"f"},
             {"id":"","source":"a","target":"b"}]
            """);
        List<String> expected = request.stream()
            .map(edge -> edge.getSource() + "->" + edge.getTarget()).toList();

        // null·빈 리스트·깨진 문서·id 있음/없음이 섞인 정상 문서 모두 같은 성질을 지켜야 한다.
        List<List<Map<String, Object>>> variants = Arrays.asList(
            null,
            List.of(),
            rawList("엣지가 아닌 문자열", null, Map.of("source", 1, "target", 2)),
            rawList(
                previousEdge("e-1", "a", "b", "true"),
                previousEdge("a", "b", "false"),
                previousEdge("e-9", "x", "y", "false"),
                previousEdge("e-1", "a", "b", "true"),
                previousEdge("g", "h", null)));

        for (List<Map<String, Object>> previous : variants) {
            List<Map<String, Object>> merged = EdgeDefinitionMerger.merge(previous, request);

            assertThat(merged).hasSameSizeAs(request);
            assertThat(merged.stream()
                .map(edge -> edge.get("source") + "->" + edge.get("target")).toList())
                .isEqualTo(expected);
            // withEdgeIds가 put 하므로 원소는 가변 Map이어야 한다.
            assertThatCode(() -> merged.forEach(edge -> edge.put("id", "generated")))
                .doesNotThrowAnyException();
        }
    }

    private Map<String, Object> previousEdge(String id, String source, String target,
        String conditionType) {

        Map<String, Object> edge = previousEdge(source, target, conditionType);
        edge.put("id", id);
        return edge;
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
