package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.service.GoogleTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Google Forms 공급원(질문 칸·응답 드롭다운)과 {@link GoogleFormsReader}의 호출 모양을 고정한다 (IEUM-BE-122).
 *
 * <p>요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다 — 요청이 나가면 테스트가 깨진다.
 */
class GoogleFormsSourcesTest {

    private static final String DRIVE = "https://www.googleapis.com/auth/drive";
    private static final String SHEETS = "https://www.googleapis.com/auth/spreadsheets";
    private static final String FORMS_API = "https://forms.googleapis.com/v1/forms/";

    /** "답 값 정규화 계약" 고정 예시 1의 form */
    private static final String FORM_JSON = """
        {"items": [
          {"title": "이름", "questionItem": {"question": {"questionId": "1a2b3c4d", "textQuestion": {}}}},
          {"title": "관심 분야", "questionItem": {"question": {"questionId": "2b3c4d5e", "choiceQuestion": {"type": "CHECKBOX"}}}},
          {"title": "만족도", "questionItem": {"question": {"questionId": "3c4d5e6f", "scaleQuestion": {"low": 1, "high": 5}}}},
          {"title": "섹션 2", "pageBreakItem": {}},
          {"title": "시간대", "questionGroupItem": {
            "questions": [{"questionId": "4d5e6f70", "rowQuestion": {"title": "오전"}},
                          {"questionId": "5e6f7081", "rowQuestion": {"title": "오후"}}],
            "grid": {"columns": {"type": "CHECKBOX"}}}},
          {"title": "첨부", "questionItem": {"question": {"questionId": "6f708192", "fileUploadQuestion": {"folderId": "f"}}}},
          {"title": "성별", "questionItem": {"question": {"questionId": "708192a3", "choiceQuestion": {"type": "RADIO"}}}},
          {"title": "별점", "questionItem": {"question": {"questionId": "8192a3b4", "ratingQuestion": {"ratingScaleLevel": 5}}}},
          {"title": "메모", "questionItem": {"question": {"questionId": "ab-12", "textQuestion": {}}}}
        ]}""";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GoogleTokenService tokenService = mock(GoogleTokenService.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final GoogleApiReader reader = new GoogleApiReader(tokenService, accounts, restTemplate);
    private final GoogleFormsReader formsReader = new GoogleFormsReader(reader);
    private final GoogleFormQuestionsFieldSource questionFields = new GoogleFormQuestionsFieldSource(formsReader);
    private final GoogleFormResponsesOptionSource formResponses = new GoogleFormResponsesOptionSource(formsReader);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private void connected(String scopes) {
        given(accounts.findByUserIdAndProvider(userId, AuthProvider.GOOGLE)).willReturn(Optional.of(
            ConnectedAccount.builder().userId(userId).provider(AuthProvider.GOOGLE)
                .accessToken("encrypted").scopes(scopes).build()));
        given(tokenService.getValidAccessToken(userId)).willReturn("g-token");
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("질문 칸 — 답 종류별 type·list, 격자는 행마다 '항목 - 행', 참조식 글자셋 밖 ID(ab-12)는 뺀다")
    void formQuestionsFieldShapes() {
        connected("openid " + DRIVE);
        server.expect(requestTo(startsWith(FORMS_API + "1FAIp_-9?fields=")))
            .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess(FORM_JSON, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> fields = questionFields.fetch(userId, Map.of("form_id", " 1FAIp_-9 "));

        assertThat(fields).extracting(f -> f.get("key")).containsExactly(
            "1a2b3c4d", "2b3c4d5e", "3c4d5e6f", "4d5e6f70", "5e6f7081", "6f708192", "708192a3", "8192a3b4");
        assertThat(fields.get(0)).isEqualTo(Map.of("key", "1a2b3c4d", "title", "이름", "type", "string", "required", false));
        assertThat(fields.get(1)).isEqualTo(
            Map.of("key", "2b3c4d5e", "title", "관심 분야", "type", "string", "list", true, "required", false));
        assertThat(fields.get(2)).containsEntry("type", "number");
        assertThat(fields.get(3)).containsEntry("title", "시간대 - 오전").containsEntry("list", true);
        assertThat(fields.get(4)).containsEntry("title", "시간대 - 오후");
        assertThat(fields.get(5)).containsEntry("type", "object").containsEntry("list", true);
        assertThat((List<Map<String, Object>>) fields.get(5).get("children")).extracting(c -> c.get("key"))
            .containsExactly("fileId", "fileName", "mimeType");
        assertThat(fields.get(6)).containsEntry("type", "string").doesNotContainKey("list");
        assertThat(fields.get(7)).containsEntry("type", "number");
    }

    @Test
    @DisplayName("질문 파싱은 ID 글자셋으로 거르지 않는다(ab-12 포함) — 거르는 건 소비자, 제목이 없으면 칸 title은 질문 ID")
    void parseQuestionsKeepsAllIds() throws Exception {
        JsonNode form = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
            {"items":[{"questionItem":{"question":{"questionId":"q1","textQuestion":{}}}},
                      {"title":"메모","questionItem":{"question":{"questionId":"ab-12","textQuestion":{}}}},
                      {"title":"이미지","imageItem":{}}]}""");

        assertThat(GoogleFormsReader.parseQuestions(form)).containsExactly(
            new GoogleFormsReader.FormQuestion("q1", "", GoogleFormsReader.AnswerKind.TEXT),
            new GoogleFormsReader.FormQuestion("ab-12", "메모", GoogleFormsReader.AnswerKind.TEXT));
    }

    @Test
    @DisplayName("제목 없는 질문의 칸 title은 질문 ID")
    void formQuestionsUntitledUsesId() {
        connected("openid " + DRIVE);
        server.expect(requestTo(startsWith(FORMS_API + "f1?fields=")))
            .andRespond(withSuccess("{\"items\":[{\"questionItem\":{\"question\":{\"questionId\":\"q1\"}}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(questionFields.fetch(userId, Map.of("form_id", "f1")))
            .containsExactly(Map.<String, Object>of("key", "q1", "title", "q1", "type", "string", "required", false));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{{nodes.a.output.formId}}", "../x", "a/b", "폼"})
    @DisplayName("폼 ID가 없거나 참조식·형식 오류면 토큰 조회·Google 호출 없이 INVALID_INPUT")
    void formQuestionsRejectBadFormIdBeforeTokenLookup(String formId) {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("form_id", formId);

        assertErrorCode(() -> questionFields.fetch(userId, inputs), ErrorCode.INVALID_INPUT);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("questions()를 직접 불러도 폼 ID는 경로에 엄격 인코딩된다 — '/'가 경로를 벗어나지 않는다(listResponses와 같음)")
    void questionsEncodesFormIdStrictly() {
        connected("openid " + DRIVE);
        server.expect(requestTo(startsWith(FORMS_API + "..%2Fx?fields=")))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(formsReader.questions(userId, "../x")).isEmpty();
    }

    @Test
    @DisplayName("응답 단건 조회 — 폼 ID·응답 ID 모두 엄격 인코딩, 본문을 그대로 돌려준다")
    void responseGetEncodesIds() {
        connected("openid " + DRIVE);
        server.expect(requestTo(FORMS_API + "1FAIp_-9/responses/a%2Fb%3Fc"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"responseId\":\"a/b?c\",\"answers\":{}}", MediaType.APPLICATION_JSON));

        assertThat(formsReader.response(userId, "1FAIp_-9", "a/b?c"))
            .hasValueSatisfying(r -> assertThat(r.path("responseId").asText()).isEqualTo("a/b?c"));
    }

    @Test
    @DisplayName("응답 단건 조회 — 404(지워진 응답)면 empty, 그 밖 오류는 그대로")
    void responseGetNotFoundIsEmpty() {
        connected("openid " + DRIVE);
        server.expect(requestTo(FORMS_API + "f1/responses/gone")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(FORMS_API + "f1/responses/r1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(formsReader.response(userId, "f1", "gone")).isEmpty();
        assertErrorCode(() -> formsReader.response(userId, "f1", "r1"), ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("spreadsheets scope만 있으면 GOOGLE_SCOPE_REQUIRED — drive가 필요하다")
    void formQuestionsNeedDriveScope() {
        connected("openid " + SHEETS);

        assertErrorCode(() -> questionFields.fetch(userId, Map.of("form_id", "f1")), ErrorCode.GOOGLE_SCOPE_REQUIRED);
    }

    @Test
    @DisplayName("없는 폼(404) → GOOGLE_RESOURCE_NOT_FOUND")
    void formQuestionsUnknownFormIsNotFound() {
        connected("openid " + DRIVE);
        server.expect(requestTo(startsWith(FORMS_API + "f1?fields=")))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertErrorCode(() -> questionFields.fetch(userId, Map.of("form_id", "f1")),
            ErrorCode.GOOGLE_RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("응답 목록 — filter는 엄격 인코딩, fields가 null이면 안 싣고, nextPageToken을 따라가 순서 그대로")
    void listResponsesFollowsPages() {
        connected("openid " + DRIVE);
        server.expect(requestTo(startsWith(FORMS_API + "1FAIp_-9/responses?")))
            .andExpect(requestTo(containsString("filter=timestamp%20%3E%3D%202026-11-10T00%3A00%3A00Z")))
            .andExpect(requestTo(not(containsString("fields="))))
            .andExpect(requestTo(not(containsString("pageToken="))))
            .andRespond(withSuccess("{\"responses\":[{\"responseId\":\"a\"},{\"responseId\":\"b\"}],"
                + "\"nextPageToken\":\"p2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("pageToken=p2")))
            .andRespond(withSuccess("{\"responses\":[{\"responseId\":\"c\"}]}", MediaType.APPLICATION_JSON));

        List<JsonNode> responses =
            formsReader.listResponses(userId, "1FAIp_-9", "timestamp >= 2026-11-10T00:00:00Z", null);

        assertThat(responses).extracting(r -> r.path("responseId").asText()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("응답 목록 — 4페이지 모두 nextPageToken이 있으면 5번째 요청 없이 INVALID_INPUT")
    void listResponsesCapsPages() {
        connected("openid " + DRIVE);
        for (int i = 0; i < GoogleFormsReader.MAX_PAGES; i++) {
            server.expect(requestTo(startsWith(FORMS_API + "f1/responses")))
                .andRespond(withSuccess("{\"responses\":[{\"responseId\":\"r" + i + "\"}],\"nextPageToken\":\"p\"}",
                    MediaType.APPLICATION_JSON));
        }

        assertErrorCode(() -> formsReader.listResponses(userId, "f1", null, null), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("공급원 키는 카탈로그 fieldsSource와 1:1")
    void formQuestionsKeyMatchesCatalog() {
        assertThat(questionFields.key()).isEqualTo("google.form_questions");
    }

    @Test
    @DisplayName("응답 드롭다운 — 최근 30일·가벼운 fields, 제출 시각 역순, 이름 'KST 시각 · 이메일'(이메일 없으면 시각만), nextCursor null")
    void formResponsesRecentFirstWithEmail() {
        connected("openid " + DRIVE);
        server.expect(requestTo(startsWith(FORMS_API + "1FAIp_-9/responses?")))
            .andExpect(requestTo(containsString("filter=timestamp%20%3E%3D%20")))
            .andExpect(requestTo(containsString(
                "fields=nextPageToken%2Cresponses%28responseId%2ClastSubmittedTime%2CrespondentEmail%29")))
            .andRespond(withSuccess("""
                {"responses":[
                  {"responseId":"r1","lastSubmittedTime":"2026-11-09T09:30:00Z"},
                  {"responseId":"r2","lastSubmittedTime":"2026-11-10T01:00:00.123Z","respondentEmail":"kim@example.com"}]}""",
                MediaType.APPLICATION_JSON));

        OptionPage page = formResponses.fetch(userId, Map.of("form_id", "1FAIp_-9"), "ignored");

        assertThat(page.items()).containsExactly(
            new OptionItem("r2", "2026-11-10 10:00 · kim@example.com"),
            new OptionItem("r1", "2026-11-09 18:30"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("응답이 20개를 넘으면 최근 20개만")
    void formResponsesCapAt20() {
        connected("openid " + DRIVE);
        String responses = IntStream.range(0, 25)
            .mapToObj(i -> "{\"responseId\":\"r%02d\",\"lastSubmittedTime\":\"2026-11-10T01:%02d:00Z\"}".formatted(i, i))
            .collect(Collectors.joining(",", "{\"responses\":[", "]}"));
        server.expect(requestTo(startsWith(FORMS_API + "f1/responses?")))
            .andRespond(withSuccess(responses, MediaType.APPLICATION_JSON));

        List<OptionItem> items = formResponses.fetch(userId, Map.of("form_id", "f1"), null).items();

        assertThat(items).hasSize(20);
        assertThat(items.get(0)).isEqualTo(new OptionItem("r24", "2026-11-10 10:24"));
        assertThat(items.get(19).id()).isEqualTo("r05");
    }

    @Test
    @DisplayName("참조식 form_id는 토큰 조회 없이 INVALID_INPUT")
    void formResponsesRejectReferenceFormId() {
        assertErrorCode(() -> formResponses.fetch(userId, Map.of("form_id", "{{nodes.a.output.id}}"), null),
            ErrorCode.INVALID_INPUT);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("드롭다운 키는 카탈로그 optionsSource와 1:1")
    void formResponsesKeyMatchesCatalog() {
        assertThat(formResponses.key()).isEqualTo("google.form_responses");
    }
}
