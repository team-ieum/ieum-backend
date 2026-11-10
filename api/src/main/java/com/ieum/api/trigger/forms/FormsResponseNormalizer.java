package com.ieum.api.trigger.forms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.api.integration.options.GoogleFormsReader.AnswerKind;
import com.ieum.api.integration.options.GoogleFormsReader.FormQuestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Forms 응답 1건 → 트리거 출력. 규칙은 agent {@code tools/google_forms.py _normalize_response}와 같다
 * (고정 예시 {@code forms/normalize_case1.json}을 양쪽이 공유). T2의 {@code isUpdate}는 핸들러가 덧붙인다.
 */
final class FormsResponseNormalizer {

    static final int MAX_TEXT = 10_000;
    static final int BUDGET_TEXT = 1_000;
    static final int BUDGET_BYTES = 2_000_000;
    /** {@code matches()}로만 쓴다 — {@code find()}+{@code $}는 끝 개행("4\n")을 숫자로 받아 agent fullmatch와 갈린다 */
    static final Pattern NUMBER = Pattern.compile("^-?[0-9]{1,15}$");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FILE_KEYS = List.of("fileId", "fileName", "mimeType");

    private FormsResponseNormalizer() {
    }

    /** 결과가 {@link #BUDGET_BYTES}를 넘으면 글 상한을 {@link #BUDGET_TEXT}로 낮춰 한 번만 다시 만든다 */
    static Map<String, Object> normalize(JsonNode response, List<FormQuestion> questions) {
        Map<String, Object> out = build(response, questions, MAX_TEXT);
        try {
            if (JSON.writeValueAsBytes(out).length > BUDGET_BYTES) {
                out = build(response, questions, BUDGET_TEXT);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);   // String·List·Map·Number만 담는다 — 일어나지 않는다
        }
        return out;
    }

    private static Map<String, Object> build(JsonNode response, List<FormQuestion> questions, int cap) {
        JsonNode raw = response.path("answers");
        Map<String, AnswerKind> kinds = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (FormQuestion q : questions) {
            if (raw.has(q.questionId())) {
                kinds.putIfAbsent(q.questionId(), q.kind());
                titles.putIfAbsent(q.questionId(), q.title());
            }
        }
        List<String> order = new ArrayList<>(titles.keySet());
        TreeSet<String> orphans = new TreeSet<>();   // 지운 질문은 ID 오름차순으로 맨 뒤
        raw.fieldNames().forEachRemaining(id -> {
            if (!titles.containsKey(id)) {
                orphans.add(id);
            }
        });
        order.addAll(orphans);

        Map<String, Object> answers = new LinkedHashMap<>();
        List<Map<String, Object>> answerList = new ArrayList<>();
        for (String id : order) {
            Object value = value(raw.get(id), kinds.get(id), cap);
            if (GoogleFormsReader.ANSWER_KEY.matcher(id).matches()) {
                answers.put(id, value);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("questionId", id);
            entry.put("question", titles.getOrDefault(id, ""));
            entry.put("answer", value);
            answerList.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("responseId", text(response, "responseId"));
        out.put("submittedAt", text(response, "lastSubmittedTime"));
        out.put("createdAt", text(response, "createTime"));
        out.put("respondentEmail", text(response, "respondentEmail"));
        out.put("answers", answers);
        out.put("answerList", answerList);
        out.put("totalScore", response.hasNonNull("totalScore") ? response.get("totalScore").numberValue() : null);
        return out;
    }

    /** kind null = 폼에 없는(지운) 질문 — 답 모양으로 정한다 */
    private static Object value(JsonNode answer, AnswerKind kind, int cap) {
        if (kind == AnswerKind.FILES || answer.has("fileUploadAnswers")) {
            List<Map<String, String>> files = new ArrayList<>();
            for (JsonNode f : answer.path("fileUploadAnswers").path("answers")) {
                Map<String, String> file = new LinkedHashMap<>();
                FILE_KEYS.forEach(k -> file.put(k, cut(f.path(k).asText(""), cap)));
                files.add(file);
            }
            return files;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode a : answer.path("textAnswers").path("answers")) {
            values.add(cut(a.path("value").asText(""), cap));
        }
        if (kind == null) {
            kind = values.size() > 1 ? AnswerKind.TEXT_LIST : AnswerKind.TEXT;
        }
        if (kind == AnswerKind.TEXT_LIST) {
            return values;
        }
        String first = values.isEmpty() ? "" : values.get(0);
        return kind == AnswerKind.NUMBER && NUMBER.matcher(first).matches() ? Long.parseLong(first) : first;
    }

    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).asText() : "";
    }

    /** code point 기준(UTF-16 길이 아님) — agent {@code s[:cap]}과 같다 */
    private static String cut(String s, int cap) {
        return s.codePointCount(0, s.length()) <= cap ? s : s.substring(0, s.offsetByCodePoints(0, cap));
    }
}
