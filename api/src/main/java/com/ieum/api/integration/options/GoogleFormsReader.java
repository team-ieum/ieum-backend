package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google Forms 호출(폼 ID 검사·질문 파싱·응답 목록)을 한 곳에 둔다. 공급원과 Forms 트리거가 공유한다.
 * scope는 {@code drive}만 통과({@link GoogleFormsOptionSource}와 같다).
 *
 * <p>트랜잭션을 걸지 않는다 — 토큰 갱신 저장이 readOnly 트랜잭션에 합류해 유실된다({@link GoogleApiReader}).
 */
@Component
@RequiredArgsConstructor
public class GoogleFormsReader {

    /** 참조식({@code answers.<id>})에 쓸 수 있는 질문 ID 글자셋 */
    public static final Pattern ANSWER_KEY = Pattern.compile("^[a-zA-Z0-9_]+$");
    static final int MAX_PAGES = 4;

    private static final String FORMS_API = "https://forms.googleapis.com/v1/forms";
    private static final String QUESTION_FIELDS =
        "items(title,questionItem/question,questionGroupItem(questions,grid/columns/type))";
    private static final Pattern FORM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final int MAX_TITLE = 1_000;

    private final GoogleApiReader googleApiReader;

    public enum AnswerKind { TEXT, TEXT_LIST, NUMBER, FILES }

    public record FormQuestion(String questionId, String title, AnswerKind kind) {
    }

    /** strip → 빈 값 → 참조식 → 형식 순으로 거절(INVALID_INPUT). URL 경로에 끼우는 값이라 토큰 조회 전에 부른다 */
    public static String formId(String raw) {
        String id = raw == null ? "" : raw.strip();
        if (id.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "폼을 먼저 골라 주세요.");
        }
        if (id.contains("{{")) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "변수 참조로 지정된 폼은 실행 전이라 불러올 수 없어요.");
        }
        if (!FORM_ID.matcher(id).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "폼 ID 형식이 올바르지 않습니다.");
        }
        return id;
    }

    /** {@code forms.get} 응답 → 질문 목록(폼 순서). ID 글자셋으로 거르지 않는다 — 거르는 건 소비자 */
    public static List<FormQuestion> parseQuestions(JsonNode form) {
        List<FormQuestion> questions = new ArrayList<>();
        for (JsonNode item : form.path("items")) {
            String title = item.path("title").asText("");
            JsonNode question = item.path("questionItem").path("question");
            if (question.hasNonNull("questionId")) {
                questions.add(new FormQuestion(question.get("questionId").asText(), truncate(title), kindOf(question)));
                continue;
            }
            JsonNode group = item.path("questionGroupItem");
            AnswerKind rowKind = "CHECKBOX".equals(group.path("grid").path("columns").path("type").asText())
                ? AnswerKind.TEXT_LIST : AnswerKind.TEXT;
            for (JsonNode row : group.path("questions")) {
                if (row.hasNonNull("questionId")) {
                    questions.add(new FormQuestion(row.get("questionId").asText(),
                        truncate(title + " - " + row.path("rowQuestion").path("title").asText("")), rowKind));
                }
            }
        }
        return questions;
    }

    public List<FormQuestion> questions(UUID userId, String formId) {
        JsonNode form = googleApiReader.get(userId,
            UriComponentsBuilder.fromUriString(FORMS_API + "/{id}")
                .queryParam("fields", QUESTION_FIELDS)
                .encode().buildAndExpand(formId).toUri(),   // 변수 먼저 엄격 인코딩 — listResponses와 같다
            GoogleApiReader.DRIVE_SCOPE);
        return parseQuestions(form);
    }

    /**
     * {@code responses.list} 전 페이지(최대 {@link #MAX_PAGES})의 {@code responses[]} 원소를 순서 그대로.
     * pageSize는 생략(서버 최대 5,000). 상한을 넘으면 INVALID_INPUT.
     *
     * @param filter null이면 싣지 않는다
     * @param fields null이면 싣지 않는다
     */
    public List<JsonNode> listResponses(UUID userId, String formId, String filter, String fields) {
        List<JsonNode> responses = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            if (++pages > MAX_PAGES) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "응답이 너무 많아 다 읽지 못했어요.");
            }
            // 값은 URI 변수로 넣어 엄격 인코딩한다 — filter의 공백·>=·: 와 pageToken의 &·=가 새지 않는다.
            Map<String, String> vars = new HashMap<>();
            vars.put("id", formId);
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(FORMS_API + "/{id}/responses");
            if (filter != null) {
                builder.queryParam("filter", "{filter}");
                vars.put("filter", filter);
            }
            if (fields != null) {
                builder.queryParam("fields", "{fields}");
                vars.put("fields", fields);
            }
            if (pageToken != null) {
                builder.queryParam("pageToken", "{pageToken}");
                vars.put("pageToken", pageToken);
            }
            JsonNode page = googleApiReader.get(userId, builder.encode().buildAndExpand(vars).toUri(),
                GoogleApiReader.DRIVE_SCOPE);
            page.path("responses").forEach(responses::add);
            pageToken = page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
        } while (pageToken != null);
        return responses;
    }

    /**
     * {@code responses.get} 단건(본문 전체). 목록은 가벼운 fields로 고르고 고른 건만 이걸로 읽는다 — 밀린 응답 본문 전체를
     * 매번 받지 않게. 404(그 사이 삭제)는 empty — 403도 같은 코드로 오지만 권한을 잃었으면 앞선 목록 조회가 먼저 실패한다.
     */
    public Optional<JsonNode> response(UUID userId, String formId, String responseId) {
        try {
            return Optional.of(googleApiReader.get(userId,
                UriComponentsBuilder.fromUriString(FORMS_API + "/{id}/responses/{responseId}")
                    .encode().buildAndExpand(formId, responseId).toUri(),   // 두 ID 모두 엄격 인코딩
                GoogleApiReader.DRIVE_SCOPE));
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.GOOGLE_RESOURCE_NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static AnswerKind kindOf(JsonNode question) {
        if ("CHECKBOX".equals(question.path("choiceQuestion").path("type").asText())) {
            return AnswerKind.TEXT_LIST;
        }
        if (question.has("scaleQuestion") || question.has("ratingQuestion")) {
            return AnswerKind.NUMBER;
        }
        if (question.has("fileUploadQuestion")) {
            return AnswerKind.FILES;
        }
        return AnswerKind.TEXT;
    }

    private static String truncate(String s) {
        return s.codePointCount(0, s.length()) <= MAX_TITLE ? s : s.substring(0, s.offsetByCodePoints(0, MAX_TITLE));
    }
}
