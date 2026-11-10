package com.ieum.api.trigger.forms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.api.integration.options.GoogleFormsReader.AnswerKind;
import com.ieum.api.integration.options.GoogleFormsReader.FormQuestion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** "답 값 정규화 계약" — agent `_normalize_response`와 같은 결과여야 한다 (IEUM-BE-122). */
class FormsResponseNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode case1() throws IOException {
        return JSON.readTree(FormsResponseNormalizerTest.class.getResourceAsStream("/forms/normalize_case1.json"));
    }

    /** Integer/Long 차이 없이 JSON으로 비교한다 */
    private static JsonNode tree(Map<String, Object> out) throws IOException {
        return JSON.readTree(JSON.writeValueAsBytes(out));
    }

    /** {질문 ID: 글 답 목록} → 응답 JSON */
    private static ObjectNode response(Map<String, List<String>> textAnswers) {
        ObjectNode response = JSON.createObjectNode();
        ObjectNode answers = response.putObject("answers");
        textAnswers.forEach((id, values) -> {
            var list = answers.putObject(id).put("questionId", id).putObject("textAnswers").putArray("answers");
            values.forEach(v -> list.addObject().put("value", v));
        });
        return response;
    }

    @Test
    @DisplayName("고정 예시 1 — agent와 공유하는 기대값과 같다")
    void case1_fixtureMatches() throws IOException {
        JsonNode c = case1();
        Map<String, Object> out = FormsResponseNormalizer.normalize(c.get("response"),
            GoogleFormsReader.parseQuestions(c.get("form")));

        assertThat(tree(out)).isEqualTo(c.get("expected"));
        assertThat(out.keySet()).containsExactly("responseId", "submittedAt", "createdAt", "respondentEmail",
            "answers", "answerList", "totalScore");
    }

    @Test
    @DisplayName("고정 예시 2 — 이메일 없음은 \"\", 퀴즈 점수, 10,000자 자르기, 숫자 아닌 배율은 글 그대로")
    void case2_capsQuizNoEmail() throws IOException {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("1a2b3c4d", List.of("가".repeat(10_001)));
        raw.put("3c4d5e6f", List.of("N/A"));
        raw.put("708192a3", List.of("남"));
        ObjectNode response = response(raw).put("responseId", "R2").put("createTime", "2026-11-10T02:00:00Z")
            .put("lastSubmittedTime", "2026-11-10T03:00:00Z").put("totalScore", 7);

        JsonNode out = tree(FormsResponseNormalizer.normalize(response,
            GoogleFormsReader.parseQuestions(case1().get("form"))));

        assertThat(out.get("respondentEmail").asText()).isEmpty();
        assertThat(out.get("submittedAt").asText()).isEqualTo("2026-11-10T03:00:00Z");
        assertThat(out.get("createdAt").asText()).isEqualTo("2026-11-10T02:00:00Z");
        assertThat(out.get("answers")).isEqualTo(JSON.createObjectNode()
            .put("1a2b3c4d", "가".repeat(10_000)).put("3c4d5e6f", "N/A").put("708192a3", "남"));
        assertThat(out.get("answerList")).extracting(e -> e.get("questionId").asText())
            .containsExactly("1a2b3c4d", "3c4d5e6f", "708192a3");
        assertThat(out.get("answerList").get(2).get("question").asText()).isEqualTo("성별");
        assertThat(out.get("totalScore").intValue()).isEqualTo(7);
    }

    static Stream<Arguments> numbers() {
        return Stream.of(
            Arguments.of("4", JSON.getNodeFactory().numberNode(4)),
            Arguments.of("-3", JSON.getNodeFactory().numberNode(-3)),
            Arguments.of("123456789012345", JSON.getNodeFactory().numberNode(123456789012345L)),
            Arguments.of("4.5", JSON.getNodeFactory().textNode("4.5")),
            Arguments.of("1234567890123456", JSON.getNodeFactory().textNode("1234567890123456")),
            Arguments.of("4\n", JSON.getNodeFactory().textNode("4\n")),
            Arguments.of("abc\n", JSON.getNodeFactory().textNode("abc\n")),
            Arguments.of("", JSON.getNodeFactory().textNode("")));
    }

    @ParameterizedTest
    @MethodSource("numbers")
    @DisplayName("숫자 칸 — 15자리 이하 정수만 숫자, 나머지(끝 개행 포함)는 글 — agent fullmatch와 같다")
    void numberRule(String raw, JsonNode expected) throws IOException {
        JsonNode out = tree(FormsResponseNormalizer.normalize(response(Map.of("n1", List.of(raw))),
            List.of(new FormQuestion("n1", "점수", AnswerKind.NUMBER))));

        assertThat(out.get("answers").get("n1")).isEqualTo(expected);
    }

    @Test
    @DisplayName("자르기는 code point 기준 — 이모지 10,001개 → 10,000개, 깨진 surrogate 없음")
    void truncatesByCodePoint() {
        Map<String, Object> out = FormsResponseNormalizer.normalize(response(Map.of("t1", List.of("😀".repeat(10_001)))),
            List.of(new FormQuestion("t1", "글", AnswerKind.TEXT)));

        assertThat(((Map<?, ?>) out.get("answers")).get("t1")).isEqualTo("😀".repeat(10_000));
    }

    @Test
    @DisplayName("직렬화가 2MB를 넘으면 글 상한 1,000으로 다시 만든다")
    void budgetRecutsTo1000() throws IOException {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        List<FormQuestion> questions = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            raw.put("q" + i, List.of("가".repeat(10_000)));
            questions.add(new FormQuestion("q" + i, "질문" + i, AnswerKind.TEXT));
        }

        JsonNode out = tree(FormsResponseNormalizer.normalize(response(raw), questions));

        assertThat(out.get("answerList")).hasSize(300)
            .allSatisfy(e -> assertThat(e.get("answer").asText()).isEqualTo("가".repeat(1_000)));
    }

    @Test
    @DisplayName("2MB 이하면 10,000자 상한 그대로")
    void underBudgetKeeps10000() {
        Map<String, Object> out = FormsResponseNormalizer.normalize(
            response(Map.of("q1", List.of("가".repeat(10_000)))),
            List.of(new FormQuestion("q1", "질문", AnswerKind.TEXT)));

        assertThat(((Map<?, ?>) out.get("answers")).get("q1")).isEqualTo("가".repeat(10_000));
    }

    @Test
    @DisplayName("격자 RADIO 행은 글 하나, CHECKBOX 행은 글 목록")
    void gridRowKinds() throws IOException {
        JsonNode form = JSON.readTree("""
            {"items":[
              {"title":"라디오","questionGroupItem":{"questions":[{"questionId":"r1","rowQuestion":{"title":"행"}}],
                "grid":{"columns":{"type":"RADIO"}}}},
              {"title":"체크","questionGroupItem":{"questions":[{"questionId":"c1","rowQuestion":{"title":"행"}}],
                "grid":{"columns":{"type":"CHECKBOX"}}}}]}""");
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("r1", List.of("A"));
        raw.put("c1", List.of("B"));

        JsonNode out = tree(FormsResponseNormalizer.normalize(response(raw), GoogleFormsReader.parseQuestions(form)));

        assertThat(out.get("answers").get("r1").asText()).isEqualTo("A");
        assertThat(out.get("answers").get("c1")).isEqualTo(JSON.createArrayNode().add("B"));
        assertThat(out.get("answerList").get(0).get("question").asText()).isEqualTo("라디오 - 행");
    }

    @Test
    @DisplayName("폼에 없는 질문 둘은 answerList 끝에 questionId 오름차순 — 응답 순서(zz9→aa1)가 아니다(agent와 같은 결과)")
    void orphanAnswersSortedById() throws IOException {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("zz9", List.of("X", "Y"));
        raw.put("aa1", List.of("Z"));
        raw.put("q1", List.of("Q"));

        JsonNode out = tree(FormsResponseNormalizer.normalize(response(raw),
            List.of(new FormQuestion("q1", "질문", AnswerKind.TEXT))));

        assertThat(out.get("answerList")).extracting(e -> e.get("questionId").asText())
            .containsExactly("q1", "aa1", "zz9");
        assertThat(out.get("answers").get("zz9")).isEqualTo(JSON.createArrayNode().add("X").add("Y"));
        assertThat(out.get("answers").get("aa1").asText()).isEqualTo("Z");
        assertThat(out.get("answerList").get(1).get("question").asText()).isEmpty();
    }
}
