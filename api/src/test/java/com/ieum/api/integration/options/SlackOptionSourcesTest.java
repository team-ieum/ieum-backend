package com.ieum.api.integration.options;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.service.SlackApiClient;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.SlackTokenProvider;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;

/**
 * 드롭다운 Slack 채널 공급원의 conversations.list 호출 모양·검색·ok:false 매핑을 고정한다(spec §10).
 * 요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다 — 요청이 나가면 테스트가 깨진다.
 */
class SlackOptionSourcesTest {

    private static final String LIST_URL = "https://slack.com/api/conversations.list";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final SlackTokenProvider tokenProvider = mock(SlackTokenProvider.class);
    private final SlackChannelOptionSource channels =
        new SlackChannelOptionSource(tokenProvider, new SlackApiClient(restTemplate));
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private void connected() {
        given(tokenProvider.getValidBotToken(userId)).willReturn("xoxb-bot");
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }

    /** conversations.list 성공 응답 — 채널 id는 C_<이름> */
    private static String page(String nextCursor, String... names) {
        String list = Stream.of(names).map(n -> "{\"id\":\"C_" + n + "\",\"name\":\"" + n + "\"}").collect(joining(","));
        return "{\"ok\":true,\"channels\":[" + list + "],\"response_metadata\":{\"next_cursor\":\"" + nextCursor + "\"}}";
    }

    /** 요청 form에 이 키가 없어야 한다 */
    private static RequestMatcher noFormKey(String key) {
        return request -> assertThat(((MockClientHttpRequest) request).getBodyAsString()).doesNotContain(key + "=");
    }

    private void expectList(Map<String, String> form, RequestMatcher extra, String response) {
        server.expect(requestTo(LIST_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer xoxb-bot"))
            .andExpect(content().formDataContains(form))
            .andExpect(extra)
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("키는 slack.channels")
    void key() {
        assertThat(channels.key()).isEqualTo("slack.channels");
    }

    @Test
    @DisplayName("q 없음 — 공개·보관 제외·20개, 첫 페이지면 cursor 없음. 항목은 (채널 ID, #이름), next_cursor 그대로")
    void listsFirstPage() {
        connected();
        expectList(Map.of("types", "public_channel", "exclude_archived", "true", "limit", "20"), noFormKey("cursor"),
            page("dGVhbTpDMDYx", "general", "random"));

        OptionPage result = channels.fetch(userId, Map.of(), null);

        assertThat(result.items()).containsExactly(
            new OptionItem("C_general", "#general"), new OptionItem("C_random", "#random"));
        assertThat(result.nextCursor()).isEqualTo("dGVhbTpDMDYx");
    }

    @Test
    @DisplayName("cursor는 그대로 넘기고, next_cursor가 빈 문자열이면 마지막 페이지(null)")
    void passesCursorAndEndsOnEmpty() {
        connected();
        expectList(Map.of("limit", "20", "cursor", "cur-2"), request -> { }, page("", "dev"));

        assertThat(channels.fetch(userId, Map.of(), "cur-2").nextCursor()).isNull();
    }

    @Test
    @DisplayName("Slack이 빈 페이지에 cursor를 줘도 그대로 — items가 비어도 nextCursor가 있을 수 있다")
    void emptyPageKeepsCursor() {
        connected();
        expectList(Map.of("limit", "20"), request -> { }, page("cur-3"));

        OptionPage result = channels.fetch(userId, Map.of(), null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isEqualTo("cur-3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "#", " # "})
    @DisplayName("공백뿐인 q·#만 있는 q는 없는 것으로 본다 — 20개씩, cursor 그대로")
    void blankQueryIsNoQuery(String q) {
        connected();
        expectList(Map.of("limit", "20", "cursor", "cur-2"), request -> { }, page("", "general"));

        channels.fetch(userId, Map.of("q", q), "cur-2");
    }

    @Test
    @DisplayName("q 있음 — 200개씩 최대 5페이지(1,000개)만 훑고, 못 찾으면 items=[]·nextCursor null")
    void searchScansAtMostFivePages() {
        connected();
        expectList(Map.of("types", "public_channel", "exclude_archived", "true", "limit", "200"), noFormKey("cursor"),
            page("c1", "general"));
        for (int i = 1; i <= 4; i++) {
            expectList(Map.of("limit", "200", "cursor", "c" + i), request -> { }, page("c" + (i + 1), "random" + i));
        }

        OptionPage result = channels.fetch(userId, Map.of("q", "deploy"), null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("q 있음 — 20개를 모으면 더 부르지 않는다")
    void searchStopsAtTwenty() {
        connected();
        String[] names = IntStream.rangeClosed(1, 25).mapToObj(i -> "dev-" + i).toArray(String[]::new);
        expectList(Map.of("limit", "200"), request -> { }, page("c1", names));

        OptionPage result = channels.fetch(userId, Map.of("q", "dev"), null);

        assertThat(result.items()).hasSize(20).first().isEqualTo(new OptionItem("C_dev-1", "#dev-1"));
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("[Review Focus 4] 검색어의 앞뒤 공백·앞의 #·대소문자는 무시하고 이름 부분 일치. 들어온 cursor는 쓰지 않는다")
    void searchIgnoresHashCaseAndSpaces() {
        connected();
        expectList(Map.of("limit", "200"), noFormKey("cursor"), page("", "general", "random", "gen-ai"));

        OptionPage result = channels.fetch(userId, Map.of("q", "  #GEN "), "stale-cursor");

        assertThat(result.items()).containsExactly(
            new OptionItem("C_general", "#general"), new OptionItem("C_gen-ai", "#gen-ai"));
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("Slack 미연결 → HTTP 호출 없이 SLACK_NOT_CONNECTED")
    void notConnected() {
        given(tokenProvider.getValidBotToken(userId)).willThrow(new CustomException(ErrorCode.SLACK_NOT_CONNECTED));

        assertErrorCode(() -> channels.fetch(userId, Map.of(), null), ErrorCode.SLACK_NOT_CONNECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_auth", "account_inactive", "token_revoked", "token_expired", "not_authed"})
    @DisplayName("ok:false 토큰 오류 → AUTHENTICATION_REQUIRED('Slack을 다시 연결해 주세요') — 빈 목록으로 삼키지 않는다")
    void tokenErrorsNeedReconnect(String error) {
        connected();
        expectList(Map.of("limit", "20"), request -> { }, "{\"ok\":false,\"error\":\"" + error + "\"}");

        assertThatThrownBy(() -> channels.fetch(userId, Map.of(), null))
            .isInstanceOf(CustomException.class)
            .hasMessage("Slack을 다시 연결해 주세요")
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("ok:false missing_scope → AUTHENTICATION_REQUIRED('Slack 앱 권한이 부족해요. 다시 연결해 주세요')")
    void missingScopeNeedsReconnect() {
        connected();
        expectList(Map.of("limit", "20"), request -> { }, "{\"ok\":false,\"error\":\"missing_scope\"}");

        assertThatThrownBy(() -> channels.fetch(userId, Map.of(), null))
            .hasMessage("Slack 앱 권한이 부족해요. 다시 연결해 주세요")
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ratelimited", "internal_error", "service_unavailable", "fatal_error", "request_timeout"})
    @DisplayName("ok:false 일시 장애 → SLACK_API_UNAVAILABLE")
    void transientErrorsAreUnavailable(String error) {
        connected();
        expectList(Map.of("limit", "20"), request -> { }, "{\"ok\":false,\"error\":\"" + error + "\"}");

        assertErrorCode(() -> channels.fetch(userId, Map.of(), null), ErrorCode.SLACK_API_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    @DisplayName("HTTP 429·5xx → SLACK_API_UNAVAILABLE")
    void httpFailuresAreUnavailable(int status) {
        connected();
        server.expect(requestTo(LIST_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertErrorCode(() -> channels.fetch(userId, Map.of(), null), ErrorCode.SLACK_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("네트워크 오류 → SLACK_API_UNAVAILABLE")
    void networkFailureIsUnavailable() {
        connected();
        server.expect(requestTo(LIST_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertErrorCode(() -> channels.fetch(userId, Map.of(), null), ErrorCode.SLACK_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("그 밖의 ok:false(조작된 cursor 등) → INVALID_INPUT")
    void otherErrorsAreInvalidInput() {
        connected();
        expectList(Map.of("cursor", "bad"), request -> { }, "{\"ok\":false,\"error\":\"invalid_cursor\"}");

        assertErrorCode(() -> channels.fetch(userId, Map.of(), "bad"), ErrorCode.INVALID_INPUT);
    }
}
