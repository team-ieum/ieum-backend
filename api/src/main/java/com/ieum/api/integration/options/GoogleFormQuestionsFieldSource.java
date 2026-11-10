package com.ieum.api.integration.options;

import com.ieum.api.integration.options.GoogleFormsReader.FormQuestion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Forms 트리거 출력 {@code answers}의 동적 칸 — 질문마다 칸 하나, key는 질문 ID. 참조식 글자셋 밖 ID는
 * {@code answers.<id>}로 참조할 수 없어 칸을 내지 않는다(그 답은 {@code answerList}에만 있다).
 */
@Component
@RequiredArgsConstructor
public class GoogleFormQuestionsFieldSource implements FieldSource {

    private static final List<Map<String, Object>> FILE_CHILDREN = List.of(
        Map.of("key", "fileId", "title", "파일 ID", "type", "string", "required", false),
        Map.of("key", "fileName", "title", "파일 이름", "type", "string", "required", false),
        Map.of("key", "mimeType", "title", "파일 형식", "type", "string", "required", false));

    private final GoogleFormsReader formsReader;

    @Override
    public String key() {
        return "google.form_questions";
    }

    @Override
    public List<Map<String, Object>> fetch(UUID userId, Map<String, String> inputs) {
        String formId = GoogleFormsReader.formId(inputs.get("form_id"));
        return formsReader.questions(userId, formId).stream()
            .filter(q -> GoogleFormsReader.ANSWER_KEY.matcher(q.questionId()).matches())
            .map(GoogleFormQuestionsFieldSource::field)
            .toList();
    }

    private static Map<String, Object> field(FormQuestion q) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", q.questionId());
        field.put("title", q.title().isBlank() ? q.questionId() : q.title());
        field.put("type", switch (q.kind()) {
            case TEXT, TEXT_LIST -> "string";
            case NUMBER -> "number";
            case FILES -> "object";
        });
        if (q.kind() == GoogleFormsReader.AnswerKind.TEXT_LIST || q.kind() == GoogleFormsReader.AnswerKind.FILES) {
            field.put("list", true);
        }
        field.put("required", false);
        if (q.kind() == GoogleFormsReader.AnswerKind.FILES) {
            field.put("children", FILE_CHILDREN);
        }
        return field;
    }
}
