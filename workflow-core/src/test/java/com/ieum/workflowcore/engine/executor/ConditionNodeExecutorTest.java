package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionNodeExecutorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneId.of("Asia/Seoul")); // KST 10:00
    private final ConditionNodeExecutor executor = new ConditionNodeExecutor(CLOCK);

    /**
     * 앞 노드 "ai" 출력이 있는 실행 상태에서, 런타임(prepareNodeInput)처럼 config를 renderDeep한 사본을 input으로 넘긴다.
     * node.config는 빈 Map이다 — 실행기가 input만 읽는지 모든 테스트가 함께 본다.
     */
    private ExecutorResult run(Map<String, Object> config) {
        ExecutionContext context = new ExecutionContext();
        context.setNodeOutput("ai", Map.of("urgent", true, "count", 4, "subject", "[긴급] 서버 다운",
            "amount", "120,000원", "tags", List.of("긴급", "장애")));
        return runWith(context, config);
    }

    @SuppressWarnings("unchecked")
    private ExecutorResult runWith(ExecutionContext context, Map<String, Object> config) {
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(List.of());
        cursor.setAllEdges(List.of());
        cursor.setContext(context);
        Map<String, Object> input = (Map<String, Object>) cursor.renderDeep(config);
        return executor.execute(new Node("cond", NodeType.CONDITION, "조건", new HashMap<>()), input, cursor);
    }

    private static Map<String, Object> rule(Object left, String operator, Object right) {
        Map<String, Object> r = new HashMap<>();
        r.put("left", left);
        r.put("operator", operator);
        r.put("right", right);
        return r;
    }

    @SafeVarargs
    private static Map<String, Object> branch(String key, String name, String match, Map<String, Object>... rules) {
        Map<String, Object> b = new HashMap<>();
        b.put("key", key);
        if (name != null) {
            b.put("name", name);
        }
        if (match != null) {
            b.put("match", match);
        }
        b.put("rules", Arrays.asList(rules));
        return b;
    }

    private static Map<String, Object> shape(Object branches, String pick, String otherwise) {
        Map<String, Object> s = new HashMap<>();
        if (branches != null) {
            s.put("branches", branches);
        }
        if (pick != null) {
            s.put("pick", pick);
        }
        if (otherwise != null) {
            s.put("otherwise", otherwise);
        }
        return s;
    }

    private boolean legacy(String operator, Object left, Object right) {
        Map<String, Object> config = new HashMap<>();
        config.put("operator", operator);
        config.put("left", left);
        config.put("right", right);
        ExecutorResult r = run(config);
        assertThat(r.isSuccess()).isTrue();
        return (Boolean) r.getOutput().get("result");
    }

    private boolean check(String operator, Object left, Object right) {
        ExecutorResult r = run(shape(List.of(branch("b_aaaaaaa1", "A", null, rule(left, operator, right))), null, null));
        assertThat(r.isSuccess()).isTrue();
        return (Boolean) r.getOutput().get("matched");
    }

    private static Map<String, Object> twoTrueBranches(String pick) {
        return shape(List.of(
            branch("b_aaaaaaa1", "A", null, rule("x", "isNotEmpty", null)),
            branch("b_bbbbbbb2", "B", null, rule("y", "isNotEmpty", null))), pick, null);
    }

    @Test
    @DisplayName("새 모양: 처음 맞는 갈래를 고르고 출력 5키를 spec 순서로 낸다")
    void newShape_firstMatchingBranchPicked() {
        ExecutorResult r = run(shape(List.of(
            branch("b_7f3a9c21", "긴급", "all",
                rule("{{nodes.ai.output.subject}}", "contains", "긴급"),
                rule("{{nodes.ai.output.amount}}", "greaterThan", "100000")),
            branch("b_0d44e8f3", "일반 문의", "any", rule("{{nodes.ai.output.subject}}", "in", "문의, 질문"))),
            null, null));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).containsExactly(
            entry("branch", "긴급"), entry("branches", List.of("긴급")), entry("matched", true),
            entry("result", true), entry("_branchKeys", List.of("b_7f3a9c21")));
    }

    @Test
    @DisplayName("pick 생략(first): 여러 갈래가 맞아도 첫 갈래만")
    void pickFirst_returnsOnlyFirstOfSeveralMatches() {
        ExecutorResult r = run(twoTrueBranches(null));
        assertThat(r.getOutput()).containsEntry("branches", List.of("A"))
            .containsEntry("_branchKeys", List.of("b_aaaaaaa1"));
    }

    @Test
    @DisplayName("pick all: 맞는 갈래 전부를 갈래 순서대로")
    void pickAll_returnsEveryMatchInBranchOrder() {
        ExecutorResult r = run(twoTrueBranches("all"));
        assertThat(r.getOutput()).containsEntry("branch", "A")
            .containsEntry("branches", List.of("A", "B"))
            .containsEntry("_branchKeys", List.of("b_aaaaaaa1", "b_bbbbbbb2"));
    }

    @Test
    @DisplayName("맞는 갈래가 없으면 그 외(else) 갈래")
    void noMatch_otherwiseBranch_goesElse() {
        ExecutorResult r = run(shape(List.of(branch("b_aaaaaaa1", "A", null, rule("", "isNotEmpty", null))), null, null));
        assertThat(r.getOutput()).containsExactly(
            entry("branch", "그 외"), entry("branches", List.of("그 외")), entry("matched", false),
            entry("result", false), entry("_branchKeys", List.of("else")));
    }

    @Test
    @DisplayName("otherwise stop: 맞는 갈래가 없으면 아무 갈래도 고르지 않고 성공")
    void noMatch_otherwiseStop_picksNothing() {
        ExecutorResult r = run(shape(List.of(branch("b_aaaaaaa1", "A", null, rule("", "isNotEmpty", null))), null, "stop"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).containsEntry("branch", null)
            .containsEntry("branches", List.of())
            .containsEntry("matched", false)
            .containsEntry("_branchKeys", List.of());
    }

    @Test
    @DisplayName("match any는 조건 하나, all·생략은 조건 전부")
    void matchAnyNeedsOne_matchAllNeedsEvery() {
        for (String match : new String[] {"any", "all", null}) {
            ExecutorResult r = run(shape(List.of(branch("b_aaaaaaa1", "A", match,
                rule("x", "isNotEmpty", null), rule("", "isNotEmpty", null))), null, null));
            assertThat(r.getOutput().get("matched")).as("match=%s", match).isEqualTo("any".equals(match));
        }
    }

    @Test
    @DisplayName("레거시: 참이면 true 엣지(참), 거짓이면 false 엣지(그 외)")
    void legacy_mapsToTrueFalseKeys() {
        Map<String, Object> config = new HashMap<>(Map.of(
            "operator", "greaterThan", "left", "{{nodes.ai.output.amount}}", "right", "100000"));
        assertThat(run(config).getOutput()).containsExactly(
            entry("branch", "참"), entry("branches", List.of("참")), entry("matched", true),
            entry("result", true), entry("_branchKeys", List.of("true")));

        config.put("right", "200000");
        assertThat(run(config).getOutput()).containsExactly(
            entry("branch", "그 외"), entry("branches", List.of("그 외")), entry("matched", false),
            entry("result", false), entry("_branchKeys", List.of("false")));
    }

    @Test
    @DisplayName("레거시: left·right가 없으면 leftValue·rightValue")
    void legacy_leftValueRightValueFallback() {
        ExecutorResult r = run(new HashMap<>(Map.of("operator", "equals", "leftValue", "a", "rightValue", "A")));
        assertThat(r.getOutput()).containsEntry("result", true);
    }

    @Test
    @DisplayName("레거시도 caseSensitive를 따른다 — 불리언 true·문자열 \"TRUE\"")
    void legacy_honorsCaseSensitive() {
        Map<String, Object> config = new HashMap<>(Map.of(
            "operator", "equals", "left", "True", "right", "true", "caseSensitive", true));
        assertThat(run(config).getOutput()).containsEntry("result", false);
        config.put("caseSensitive", "TRUE");
        assertThat(run(config).getOutput()).containsEntry("result", false);
    }

    @Test
    @DisplayName("레거시는 pick·otherwise를 무시한다 — 거짓이면 늘 false 엣지")
    void legacy_ignoresPickAndOtherwise() {
        ExecutorResult r = run(new HashMap<>(Map.of(
            "operator", "equals", "left", "a", "right", "b", "otherwise", "stop")));
        assertThat(r.getOutput()).containsEntry("_branchKeys", List.of("false"));
    }

    @Test
    @DisplayName("equals는 기본으로 대소문자·앞뒤 공백을 무시한다")
    void equals_ignoresCaseAndSpaceByDefault() {
        assertThat(legacy("equals", "True", " true ")).isTrue();
        assertThat(legacy("notEquals", "True", "true")).isFalse();
    }

    @Test
    @DisplayName("null은 빈 텍스트다")
    void equals_nullIsEmptyText() {
        assertThat(legacy("equals", null, "")).isTrue();
        assertThat(legacy("equals", null, null)).isTrue();
        assertThat(legacy("notEquals", null, "")).isFalse();
    }

    @Test
    @DisplayName("치환 결과 \"true\"는 우변 JSON boolean true·문자열 \"true\"와 같다")
    void equals_renderedBooleanMatchesJsonBoolean() {
        assertThat(legacy("equals", "{{nodes.ai.output.urgent}}", true)).isTrue();
        assertThat(legacy("equals", "{{nodes.ai.output.urgent}}", "true")).isTrue();
        assertThat(legacy("notEquals", "{{nodes.ai.output.urgent}}", true)).isFalse();
    }

    @Test
    @DisplayName("치환 결과 \"4\"는 우변 JSON 숫자 4·문자열 \"4\"와 같다 — 표기가 다른 4.0과는 다르다(텍스트 비교)")
    void equals_renderedNumberMatchesJsonNumber() {
        assertThat(legacy("equals", "{{nodes.ai.output.count}}", 4)).isTrue();
        assertThat(legacy("equals", "{{nodes.ai.output.count}}", "4")).isTrue();
        assertThat(legacy("equals", "{{nodes.ai.output.count}}", 4.0)).isFalse();
    }

    @Test
    @DisplayName("기존 문자열끼리 비교는 그대로다")
    void equals_plainStringsUnchanged() {
        assertThat(legacy("equals", "a", "a")).isTrue();
        assertThat(legacy("equals", "a", "b")).isFalse();
        assertThat(legacy("notEquals", "a", "b")).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource(delimiter = '|', value = {
        "contains|{{nodes.ai.output.subject}}|긴급|true",
        "notContains|{{nodes.ai.output.subject}}|문의|true",
        "startsWith|{{nodes.ai.output.subject}}|[긴급]|true",
        "endsWith|{{nodes.ai.output.subject}}|다운|true",
        "in|문의|'문의, 질문'|true",
        "notIn|기타|'문의, 질문'|true",
        "greaterThan|{{nodes.ai.output.amount}}|100000|true",
        "lessThan|15%|20|true",
        "greaterThanOrEqual|4|4.0|true",
        "lessThanOrEqual|'1,000원'|999|false",
        "numberEquals|{{nodes.ai.output.count}}|4.0|true",
        "before|2026-11-04|오늘|true",
        "after|2026-11-05T09:00:00+09:00|2026-11-05|false",
        "after|2026-11-06|2026-11-05|true",
        "sameDay|2026-11-04T16:00:00Z|2026-11-05|true",
        "isTrue|예||true",
        "isFalse|no||true",
        "isEmpty|'  '||true",
        "isNotEmpty|x||true",
        "equals|' Hello '|hello|true",
        "contains|[2026] 보고서|보고서|true"})                   // 뒤 글자 있는 JSON 배열은 목록이 아니다
    void operators(String operator, String left, String right, boolean expected) {
        assertThat(check(operator, left, right)).isEqualTo(expected);
    }

    @Test
    @DisplayName("contains: left가 JSON 목록이면 원소 전체 일치")
    void contains_onJsonListLeft_matchesWholeElement() {
        assertThat(check("contains", "{{nodes.ai.output.tags}}", "장애")).isTrue();
        assertThat(check("contains", "{{nodes.ai.output.tags}}", "애")).isFalse();
        assertThat(check("notContains", "{{nodes.ai.output.tags}}", "애")).isTrue();
    }

    @Test
    @DisplayName("numberEquals: 치환을 거치지 않은 JSON 숫자 4도 \"4.0\"과 같다")
    void numberEquals_onRealJsonNumber() {
        assertThat(check("numberEquals", 4, "4.0")).isTrue();
    }

    @Test
    @DisplayName("branches가 \"\"·null·빈 목록이면 레거시로 읽는다")
    void blankBranches_isLegacy() {
        List<Object> blanks = new ArrayList<>(Arrays.asList("", null, List.of()));
        for (Object blank : blanks) {
            Map<String, Object> config = new HashMap<>(Map.of("operator", "equals", "left", "a", "right", "A"));
            config.put("branches", blank);
            ExecutorResult r = run(config);
            assertThat(r.isSuccess()).as("branches=%s", blank).isTrue();
            assertThat(r.getOutput()).containsEntry("_branchKeys", List.of("true"));
        }
    }

    @Test
    @DisplayName("match·pick·otherwise가 \"\"·null이면 기본값")
    void blankPickOtherwiseMatch_useDefaults() {
        Map<String, Object> b = new HashMap<>();
        b.put("key", "b_aaaaaaa1");
        b.put("name", "A");
        b.put("match", "");
        b.put("rules", List.of(rule("x", "isNotEmpty", null), rule("", "isNotEmpty", null)));
        Map<String, Object> config = new HashMap<>();
        config.put("branches", List.of(b));
        config.put("pick", "");
        config.put("otherwise", null);

        ExecutorResult r = run(config);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).containsEntry("_branchKeys", List.of("else"));
    }

    @Test
    @DisplayName("in: right가 JSON 배열 문자열이거나 진짜 List여도 원소로 읽는다")
    void in_onJsonArrayOrListRight() {
        assertThat(check("in", "긴급", "[\"긴급\",\"일반\"]")).isTrue();
        assertThat(check("in", "긴급", List.of("긴급", "일반"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"contains", "notContains", "startsWith", "endsWith", "in", "notIn"})
    @DisplayName("기준값이 비면(풀리지 않은 참조식) 텍스트 연산자는 거짓, 노드는 성공")
    void emptyRight_isFalseForTextOperators(String operator) {
        assertThat(check(operator, "x", "{{nodes.missing.output.v}}")).isFalse();
    }

    @Test
    @DisplayName("못 읽는 숫자·날짜는 그 조건 거짓 — 노드는 성공")
    void unreadableValues_areFalse_nodeSucceeds() {
        assertThat(check("greaterThan", "약 1천", "100")).isFalse();
        assertThat(check("lessThan", "약 1천", "100")).isFalse();
        assertThat(check("numberEquals", "₩1,000", "1000")).isFalse();
        assertThat(check("before", "언젠가", "오늘")).isFalse();
        assertThat(check("after", "오늘", "언젠가")).isFalse();
    }

    @Test
    @DisplayName("참거짓으로 못 읽는 값은 isTrue·isFalse 둘 다 거짓")
    void isTrueIsFalse_unknownValue_bothFalse() {
        assertThat(check("isTrue", "maybe", null)).isFalse();
        assertThat(check("isFalse", "maybe", null)).isFalse();
    }

    @Test
    @DisplayName("갈래 이름이 없으면 key를 이름으로 쓴다")
    void missingName_fallsBackToKey() {
        ExecutorResult r = run(shape(List.of(branch("b_aaaaaaa1", null, null, rule("x", "isNotEmpty", null))), null, null));
        assertThat(r.getOutput()).containsEntry("branch", "b_aaaaaaa1");
    }

    @Test
    @DisplayName("한 번만 치환한다 — 치환된 값 안의 참조식은 글자 그대로다")
    void rendersOnce_valuesInsideRenderedTextStayLiteral() {
        ExecutionContext context = new ExecutionContext();
        context.setNodeOutput("ai", Map.of("subject", "{{nodes.secret.output.token}}"));
        context.setNodeOutput("secret", Map.of("token", "S"));

        ExecutorResult eq = runWith(context, shape(List.of(branch("b_aaaaaaa1", "A", null,
            rule("{{nodes.ai.output.subject}}", "equals", "S"))), null, null));
        ExecutorResult contains = runWith(context, shape(List.of(branch("b_aaaaaaa1", "A", null,
            rule("{{nodes.ai.output.subject}}", "contains", "nodes.secret"))), null, null));

        assertThat(eq.getOutput()).containsEntry("matched", false);
        assertThat(contains.getOutput()).containsEntry("matched", true);
    }

    @SuppressWarnings("unchecked")
    static Stream<Map<String, Object>> badConfigs() {
        Map<String, Object> ok = rule("x", "isNotEmpty", null);
        Map<String, Object> numericKey = branch("b_aaaaaaa1", "A", null, ok);
        numericKey.put("key", 123);
        return Stream.of(
            shape("not-a-list", null, null),
            shape(List.of("x"), null, null),
            shape(List.of(branch("", "A", null, ok)), null, null),
            shape(List.of(branch(null, "A", null, ok)), null, null),
            shape(List.of(numericKey), null, null),
            shape(List.of(branch("b_aaaaaaa1", "A", null, ok), branch("b_aaaaaaa1", "B", null, ok)), null, null),
            shape(List.of(branch("else", "A", null, ok)), null, null),
            shape(List.of(branch("true", "A", null, ok)), null, null),
            shape(List.of(branch("false", "A", null, ok)), null, null),
            shape(List.of(branch("b_aaaaaaa1", "A", null)), null, null),                    // rules 빈 목록
            shape(IntStream.range(0, 11).mapToObj(i -> branch("b_" + i, "n" + i, null, ok)).toList(), null, null),
            shape(List.of(branch("b_aaaaaaa1", "A", null, Collections.nCopies(11, ok).toArray(Map[]::new))), null, null),
            shape(List.of(branch("b_aaaaaaa1", "A", null, rule("x", "regex", "y"))), null, null),
            shape(List.of(branch("b_aaaaaaa1", "A", "most", ok)), null, null),
            shape(List.of(branch("b_aaaaaaa1", "A", null, ok)), "some", null),
            shape(List.of(branch("b_aaaaaaa1", "A", null, ok)), null, "skip"),
            new HashMap<>(Map.of("left", "a")));                                             // 레거시 operator 없음
    }

    @ParameterizedTest
    @MethodSource("badConfigs")
    @DisplayName("설정 오류는 노드 실패")
    void configErrors_failNode(Map<String, Object> config) {
        ExecutorResult r = run(config);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("공백만 있는 key는 빈 값이 아니다 — 예약어도 정확히 일치할 때만 거절")
    void blankLikeKeys_areAllowed() {
        assertThat(run(shape(List.of(branch(" ", "A", null, rule("x", "isNotEmpty", null))), null, null)).isSuccess()).isTrue();
        assertThat(run(shape(List.of(branch("Else", "A", null, rule("x", "isNotEmpty", null))), null, null)).isSuccess()).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 상세·경고 (Part B)
    // ──────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRule(ExecutorResult r) {
        Map<String, Object> branch = ((List<Map<String, Object>>) r.getDetail().get("branches")).get(0);
        return ((List<Map<String, Object>>) branch.get("rules")).get(0);
    }

    @SafeVarargs
    private ExecutorResult runRules(String match, Map<String, Object>... rules) {
        return run(shape(List.of(branch("b_7f3a9c21", "긴급", match, rules)), null, null));
    }

    @Test
    @DisplayName("상세: 전 갈래·전 조건의 판단 근거를 spec 예 그대로 남긴다")
    void detail_recordsEveryBranchAndRule() {
        ExecutorResult r = run(shape(List.of(
            branch("b_7f3a9c21", "긴급", "all",
                rule("{{nodes.ai.output.subject}}", "contains", "긴급"),
                rule("{{nodes.ai.output.amount}}", "greaterThan", "100000")),
            branch("b_0d44e8f3", "일반 문의", "any", rule("{{nodes.ai.output.subject}}", "in", "문의, 질문"))),
            null, null));
        assertThat(r.getDetail()).isEqualTo(Map.of(
            "picked", List.of("b_7f3a9c21"),
            "branches", List.of(
                Map.of("key", "b_7f3a9c21", "name", "긴급", "matched", true, "rules", List.of(
                    Map.of("left", "[긴급] 서버 다운", "operator", "contains", "right", "긴급", "passed", true),
                    Map.of("left", "120,000원", "operator", "greaterThan", "right", "100000", "passed", true))),
                Map.of("key", "b_0d44e8f3", "name", "일반 문의", "matched", false, "rules", List.of(
                    Map.of("left", "[긴급] 서버 다운", "operator", "in", "right", "문의, 질문", "passed", false)))),
            "warnings", List.of()));
    }

    @Test
    @DisplayName("상세: 레거시는 key true·이름 참, otherwise stop 불일치는 picked가 빈 목록")
    @SuppressWarnings("unchecked")
    void detail_legacyAndStop() {
        ExecutorResult legacy = run(new HashMap<>(Map.of("operator", "equals", "left", "a", "right", "a")));
        assertThat(legacy.getDetail()).containsEntry("picked", List.of("true"));
        Map<String, Object> b0 = ((List<Map<String, Object>>) legacy.getDetail().get("branches")).get(0);
        assertThat(b0).containsEntry("key", "true").containsEntry("name", "참");

        ExecutorResult stop = run(shape(List.of(branch("b_aaaaaaa1", "A", null, rule("", "isNotEmpty", null))), null, "stop"));
        assertThat(stop.getDetail()).containsEntry("picked", List.of());
    }

    @Test
    @DisplayName("상세: 설정 오류로 실패하면 null")
    void detail_nullOnConfigError() {
        ExecutorResult r = run(badConfigs().findFirst().orElseThrow());
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getDetail()).isNull();
    }

    @Test
    @DisplayName("경고: 숫자로 못 읽은 값")
    void warning_unreadableNumber() {
        ExecutorResult r = runRules(null, rule("약 1천", "greaterThan", "100000"));
        assertThat(r.getDetail()).containsEntry("warnings",
            List.of("갈래 '긴급' 1번째 조건: '약 1천'을(를) 숫자로 읽지 못해 거짓으로 봤어요."));
        assertThat(firstRule(r)).containsEntry("passed", false);
    }

    @Test
    @DisplayName("경고: 날짜로 못 읽은 값")
    void warning_unreadableDate() {
        ExecutorResult r = runRules("any", rule("x", "isNotEmpty", null), rule("언젠가", "after", "오늘"));
        assertThat(r.getDetail()).containsEntry("warnings",
            List.of("갈래 '긴급' 2번째 조건: '언젠가'을(를) 날짜로 읽지 못해 거짓으로 봤어요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"contains", "notContains", "startsWith", "endsWith", "in", "notIn"})
    @DisplayName("경고: 기준값이 비면 — notContains·notIn 포함 — 거짓 + 경고")
    void warning_emptyRight(String operator) {
        ExecutorResult r = runRules(null, rule("x", operator, "{{nodes.missing.output.v}}"));
        assertThat(r.getDetail()).containsEntry("warnings",
            List.of("갈래 '긴급' 1번째 조건: 기준값이 비어 있어 거짓으로 봤어요."));
        assertThat(firstRule(r)).containsEntry("passed", false);
    }

    @Test
    @DisplayName("경고: 참거짓으로 못 읽는 값은 경고 없이 거짓")
    void warning_noneForUnknownBoolean() {
        assertThat(runRules(null, rule("maybe", "isTrue", null)).getDetail()).containsEntry("warnings", List.of());
    }

    @Test
    @DisplayName("상세: 앞 조건이 거짓이어도 뒤 조건까지 평가한다")
    @SuppressWarnings("unchecked")
    void detail_evaluatesEveryRuleEvenAfterFalse() {
        ExecutorResult r = runRules("all", rule("a", "equals", "b"), rule("x", "isNotEmpty", null));
        Map<String, Object> b0 = ((List<Map<String, Object>>) r.getDetail().get("branches")).get(0);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) b0.get("rules");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0)).containsEntry("passed", false);
        assertThat(rules.get(1)).containsEntry("passed", true);
        assertThat(b0).containsEntry("matched", false);
    }

    @Test
    @DisplayName("상세: right를 안 쓰는 연산자는 right null을 그대로 둔다")
    void detail_keepsNullRightForUnaryOperators() {
        Map<String, Object> noRight = new HashMap<>(Map.of("left", "예", "operator", "isTrue"));
        ExecutorResult r = runRules(null, noRight);
        assertThat(r.isSuccess()).isTrue();
        assertThat(firstRule(r)).containsEntry("right", null);
    }

    @Test
    @DisplayName("상세·경고: 긴 값은 200자 + …")
    void detail_truncatesLongValues() {
        String longValue = "가".repeat(300);
        String cut = "가".repeat(200) + "…";
        assertThat(firstRule(runRules(null, rule(longValue, "isNotEmpty", null)))).containsEntry("left", cut);
        assertThat(runRules(null, rule(longValue, "greaterThan", "1")).getDetail()).containsEntry("warnings",
            List.of("갈래 '긴급' 1번째 조건: '" + cut + "'을(를) 숫자로 읽지 못해 거짓으로 봤어요."));
    }

    @Test
    @DisplayName("상세: 자르기는 서로게이트 쌍을 가르지 않는다")
    void detail_truncationKeepsSurrogatePairs() {
        String left = "a".repeat(199) + "😀" + "b".repeat(10);
        assertThat(firstRule(runRules(null, rule(left, "isNotEmpty", null)))).containsEntry("left", "a".repeat(199) + "…");
    }

    @Test
    @DisplayName("상세: 문자열이 아닌 값(JSON boolean)은 그대로")
    void detail_keepsNonStringValues() {
        ExecutorResult r = run(new HashMap<>(Map.of("operator", "equals", "left", "true", "right", true)));
        assertThat(firstRule(r)).containsEntry("right", Boolean.TRUE);
    }
}
