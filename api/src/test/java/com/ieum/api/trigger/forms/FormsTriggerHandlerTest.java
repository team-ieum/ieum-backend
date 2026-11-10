package com.ieum.api.trigger.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.GoogleApiReader;
import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.service.GoogleTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.PollStart;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class FormsTriggerHandlerTest {

    private static final String FORMS_API = "https://forms.googleapis.com/v1/forms/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORM = """
        {"items":[{"title":"이름","questionItem":{"question":{"questionId":"q1","textQuestion":{}}}}]}""";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GoogleTokenService tokenService = mock(GoogleTokenService.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final GoogleFormsReader reader =
        new GoogleFormsReader(new GoogleApiReader(tokenService, accounts, restTemplate));
    private final FormsTriggerHandler t1 = new FormsTriggerHandler(TriggerType.FORMS_NEW_RESPONSE, reader);
    private final FormsTriggerHandler t2 = new FormsTriggerHandler(TriggerType.FORMS_ANY_RESPONSE, reader);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verify() {
        server.verify();
    }

    private void connected() {
        given(accounts.findByUserIdAndProvider(userId, AuthProvider.GOOGLE)).willReturn(Optional.of(
            ConnectedAccount.builder().userId(userId).provider(AuthProvider.GOOGLE).accessToken("enc")
                .scopes("openid https://www.googleapis.com/auth/drive").build()));
        given(tokenService.getValidAccessToken(userId)).willReturn("g-token");
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    private static void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("T1·T2 type, 구독형, 노드 테스트는 FETCH, 수정 재발화는 T2만")
    void typesAndModes() {
        assertThat(t1.type()).isEqualTo(TriggerType.FORMS_NEW_RESPONSE);
        assertThat(t2.type()).isEqualTo(TriggerType.FORMS_ANY_RESPONSE);
        assertThat(t1.subscribes()).isTrue();
        assertThat(t2.subscribes()).isTrue();
        assertThat(t1.nodeTestMode()).isEqualTo(NodeTestMode.FETCH);
        assertThat(t2.nodeTestMode()).isEqualTo(NodeTestMode.FETCH);
        assertThat(t1.updates()).isFalse();
        assertThat(t2.updates()).isTrue();
    }

    @Test
    @DisplayName("스냅샷 {formId: 문자열|null} — resourceKey도 같고, JSON 왕복해도 같다(커서 리셋 없음)")
    void snapshotIsStringFormIdOrNull() throws Exception {
        Map<String, Object> snapshot = t1.snapshot(Map.of("form_id", " 1FAIp ", "brand", "google_forms"));
        assertThat(snapshot).containsExactly(Map.entry("formId", "1FAIp"));
        assertThat(t1.resourceKey(snapshot)).isEqualTo("1FAIp");

        Map<String, Object> empty = new HashMap<>();
        empty.put("formId", null);
        assertThat(t1.snapshot(Map.of())).isEqualTo(empty);
        assertThat(t1.snapshot(Map.of("form_id", "  "))).isEqualTo(empty);
        assertThat(t1.resourceKey(empty)).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> roundTrip = JSON.readValue(JSON.writeValueAsString(snapshot), Map.class);
        assertThat(t1.snapshot(Map.of("form_id", "1FAIp"))).isEqualTo(roundTrip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{nodes.a.output.id}}", "../a"})
    @DisplayName("참조식·경로 문자 form_id는 저장 때 INVALID_INPUT")
    void snapshotRejectsBadFormId(String formId) {
        assertErrorCode(() -> t1.snapshot(Map.of("form_id", formId)), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("접근 검증 — formId가 있으면 forms.get 1회, null이면 요청 없음")
    void verifyAccessCallsFormsGet() {
        connected();
        server.expect(requestTo(startsWith(FORMS_API + "1FAIp?fields=")))
            .andRespond(withSuccess(FORM, MediaType.APPLICATION_JSON));

        t1.verifyAccess(userId, Map.of("formId", "1FAIp"));
        Map<String, Object> none = new HashMap<>();
        none.put("formId", null);
        t1.verifyAccess(userId, none);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404})
    @DisplayName("접근 검증 — 401·404는 400(INVALID_INPUT)으로 바꾼다(401이면 FE가 로그아웃시킨다)")
    void verifyAccessMapsAuthAndNotFoundTo400(int status) {
        connected();
        server.expect(requestTo(startsWith(FORMS_API + "1FAIp?fields=")))
            .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertErrorCode(() -> t1.verifyAccess(userId, Map.of("formId", "1FAIp")), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("폴링 시작 — afterSec = 지금, 즉시 폴링")
    void pollStartsAtNow() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0);
        assertThat(t1.pollStart(now)).isEqualTo(
            new PollStart(Map.of("afterSec", now.atZone(ZoneId.systemDefault()).toEpochSecond()), now));
    }

    @Test
    @DisplayName("노드 테스트 — 가벼운 목록에서 제출 시각 최신 1건을 골라 그 건만 전체로 읽어 정규화, T2만 isUpdate")
    void fetchSampleNewest() {
        connected();
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(startsWith(FORMS_API + "1FAIp/responses?")))
                .andExpect(requestTo(containsString("fields=nextPageToken%2Cresponses%28responseId%2CcreateTime%2ClastSubmittedTime%29")))
                .andRespond(withSuccess("""
                    {"responses":[
                      {"responseId":"old","createTime":"2026-11-09T01:00:00Z","lastSubmittedTime":"2026-11-09T01:00:00Z"},
                      {"responseId":"bad","createTime":"x","lastSubmittedTime":"not-a-time"},
                      {"responseId":"new","createTime":"2026-11-10T01:00:00Z","lastSubmittedTime":"2026-11-10T02:00:00.5Z"}]}""",
                    MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(FORMS_API + "1FAIp/responses?")))
                .andExpect(requestTo(containsString("filter=timestamp%20%3E%3D%202026-11-10T02%3A00%3A00.5Z")))
                .andExpect(requestTo(not(containsString("fields="))))
                .andRespond(withSuccess("""
                    {"responses":[{"responseId":"new","createTime":"2026-11-10T01:00:00Z",
                      "lastSubmittedTime":"2026-11-10T02:00:00.5Z",
                      "answers":{"q1":{"questionId":"q1","textAnswers":{"answers":[{"value":"김"}]}}}}]}""",
                    MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(FORMS_API + "1FAIp?fields=")))
                .andRespond(withSuccess(FORM, MediaType.APPLICATION_JSON));
        }

        Map<String, Object> s1 = t1.fetchSample(userId, Map.of("form_id", "1FAIp")).orElseThrow();
        Map<String, Object> s2 = t2.fetchSample(userId, Map.of("form_id", "1FAIp")).orElseThrow();

        assertThat(s1).containsEntry("responseId", "new").containsEntry("answers", Map.of("q1", "김"))
            .doesNotContainKey("isUpdate");
        assertThat(s2).containsEntry("isUpdate", true);
        assertThat(s2.keySet()).last().isEqualTo("isUpdate");
    }

    @Test
    @DisplayName("노드 테스트 — 응답이 없으면 empty, 전체 조회·forms.get 없음")
    void fetchSampleEmpty() {
        connected();
        server.expect(requestTo(startsWith(FORMS_API + "1FAIp/responses?")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(t1.fetchSample(userId, Map.of("form_id", "1FAIp"))).isEmpty();
    }

    @Test
    @DisplayName("노드 테스트 — 폼을 안 고르면 요청 없이 INVALID_INPUT")
    void fetchSampleNeedsForm() {
        assertErrorCode(() -> t1.fetchSample(userId, Map.of()), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("스위퍼 도우미 — eventSec(T1 createTime·T2 lastSubmittedTime, 형식 오류 null)·dedupeKey·filter·isUpdate(1초 여유)")
    void sweepHelpers() throws Exception {
        JsonNode r = json("""
            {"responseId":"r1","createTime":"2026-11-10T01:00:00.123Z","lastSubmittedTime":"2026-11-10T01:00:05.9Z"}""");
        assertThat(t1.eventSec(r)).isEqualTo(1_794_272_400L);
        assertThat(t2.eventSec(r)).isEqualTo(1_794_272_405L);
        assertThat(t1.eventSec(json("{\"responseId\":\"x\"}"))).isNull();
        assertThat(t2.eventSec(json("{\"lastSubmittedTime\":\"어제\"}"))).isNull();
        assertThat(t1.dedupeKey(r)).isEqualTo("r1");
        assertThat(t2.dedupeKey(r)).isEqualTo("r1@2026-11-10T01:00:05.9Z");
        assertThat(t1.filter(1_794_272_400L)).isEqualTo("timestamp >= 2026-11-10T01:00:00Z");

        // 실호출 ③: 수정 안 한 응답도 소수 자릿수가 달라 문자열이 다르다 → 1초 여유로 false
        assertThat(t2.output(json("""
            {"responseId":"b","createTime":"2026-11-10T07:45:42.456Z","lastSubmittedTime":"2026-11-10T07:45:42.456957Z"}"""),
            List.of())).containsEntry("isUpdate", false);
        assertThat(t2.output(r, List.of())).containsEntry("isUpdate", true);
        assertThat(t2.output(json("{\"responseId\":\"c\"}"), List.of())).containsEntry("isUpdate", false);
        assertThat(t1.output(r, List.of())).doesNotContainKey("isUpdate");
    }
}
