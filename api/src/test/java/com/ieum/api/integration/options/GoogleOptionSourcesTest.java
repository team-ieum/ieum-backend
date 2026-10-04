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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.service.GoogleTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 드롭다운 공급원 6종의 Google 호출 모양과 오류 매핑을 고정한다 (IEUM-BE-71·72).
 *
 * <p>요청 없이 끝나야 하는 케이스(권한 없음·형식 오류)는 기대 요청을 등록하지 않는다 —
 * 요청이 나가면 {@code MockRestServiceServer}가 AssertionError를 던져 테스트가 깨진다.
 */
class GoogleOptionSourcesTest {

    private static final String DRIVE = "https://www.googleapis.com/auth/drive";
    private static final String SHEETS = "https://www.googleapis.com/auth/spreadsheets";
    private static final String WORKSHEETS_URL =
        "https://sheets.googleapis.com/v4/spreadsheets/1Bxi_-9?fields=sheets.properties.title";
    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar";
    private static final String CALENDAR_LIST_URL = "https://www.googleapis.com/calendar/v3/users/me/calendarList";
    private static final String EVENTS_PREFIX = "https://www.googleapis.com/calendar/v3/calendars/";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GoogleTokenService tokenService = mock(GoogleTokenService.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final GoogleApiReader reader = new GoogleApiReader(tokenService, accounts, restTemplate);
    private final GoogleSpreadsheetsOptionSource spreadsheets = new GoogleSpreadsheetsOptionSource(reader);
    private final GoogleWorksheetsOptionSource worksheets = new GoogleWorksheetsOptionSource(reader);
    private final GoogleCalendarsOptionSource calendars = new GoogleCalendarsOptionSource(reader);
    private final GoogleEventsOptionSource events = new GoogleEventsOptionSource(reader);
    private final GoogleFilesOptionSource files = new GoogleFilesOptionSource(reader);
    private final GoogleFoldersOptionSource folders = new GoogleFoldersOptionSource(reader);
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

    private Map<String, String> sheetInput(String spreadsheetId) {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("spreadsheet_id", spreadsheetId);
        return inputs;
    }

    private Map<String, String> calendarInput(String calendarId) {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("calendar_id", calendarId);
        return inputs;
    }

    private static RequestMatcher driveQuery(String expected) {
        return request -> assertThat(URLDecoder.decode(
            UriComponentsBuilder.fromUri(request.getURI()).build(true).getQueryParams().getFirst("q"),
            StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    @DisplayName("스프레드시트 목록 — Drive files를 스프레드시트·휴지통 제외·수정순으로 조회하고 다음 커서를 넘긴다")
    void spreadsheetsListsDriveFiles() {
        connected("openid email " + DRIVE);
        server.expect(requestTo(startsWith("https://www.googleapis.com/drive/v3/files?")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andExpect(requestTo(containsString(
                "q=mimeType%3D%27application%2Fvnd.google-apps.spreadsheet%27%20and%20trashed%3Dfalse")))
            .andExpect(requestTo(containsString("orderBy=modifiedTime%20desc")))
            .andExpect(requestTo(containsString("pageSize=50")))
            .andExpect(requestTo(containsString("fields=nextPageToken,files(id,name)")))
            .andExpect(requestTo(containsString("supportsAllDrives=true")))
            .andExpect(requestTo(containsString("includeItemsFromAllDrives=true")))
            .andExpect(requestTo(not(containsString("pageToken"))))
            .andRespond(withSuccess(
                "{\"nextPageToken\":\"tok-2\",\"files\":[{\"id\":\"1Bxi\",\"name\":\"2026 매출 장부\"}]}",
                MediaType.APPLICATION_JSON));

        OptionPage page = spreadsheets.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(new OptionItem("1Bxi", "2026 매출 장부"));
        assertThat(page.nextCursor()).isEqualTo("tok-2");
    }

    @Test
    @DisplayName("스프레드시트 목록 — cursor는 pageToken으로 나가고, 마지막 페이지면 nextCursor가 null")
    void spreadsheetsPassesCursorAndEndsWithNull() {
        connected(DRIVE);
        server.expect(requestTo(containsString("pageToken=tok-2")))
            .andRespond(withSuccess("{\"files\":[]}", MediaType.APPLICATION_JSON));

        OptionPage page = spreadsheets.fetch(userId, Map.of(), "tok-2");

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("cursor에 &·=가 섞여도 쿼리 파라미터로 새지 않고 값으로 인코딩된다")
    void spreadsheetsCursorIsStrictlyEncoded() {
        connected(DRIVE);
        server.expect(requestTo(containsString("pageToken=a%26q%3Dx")))
            .andExpect(requestTo(not(containsString("&q=x"))))
            .andRespond(withSuccess("{\"files\":[]}", MediaType.APPLICATION_JSON));

        spreadsheets.fetch(userId, Map.of(), "a&q=x");
    }

    @Test
    @DisplayName("drive.file만 있으면 drive를 충족하지 않는다 — Google·토큰 호출 없이 GOOGLE_SCOPE_REQUIRED")
    void driveFileDoesNotSatisfyDrive() {
        connected("openid " + DRIVE + ".file " + SHEETS);

        assertErrorCode(() -> spreadsheets.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("scopes가 null인 옛 행은 예외 없이 GOOGLE_SCOPE_REQUIRED")
    void nullScopesMeansScopeRequired() {
        connected(null);

        assertErrorCode(() -> spreadsheets.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
    }

    @Test
    @DisplayName("쉼표로 구분된 scopes도 토큰 단위로 인식한다")
    void commaSeparatedScopesAreParsed() {
        connected("openid," + DRIVE);
        server.expect(requestTo(startsWith("https://www.googleapis.com/drive/v3/files?")))
            .andRespond(withSuccess("{\"files\":[]}", MediaType.APPLICATION_JSON));

        spreadsheets.fetch(userId, Map.of(), null);
    }

    @Test
    @DisplayName("Google 연결이 없으면 ACCOUNT_NOT_CONNECTED")
    void noAccountMeansNotConnected() {
        given(accounts.findByUserIdAndProvider(userId, AuthProvider.GOOGLE)).willReturn(Optional.empty());

        assertErrorCode(() -> spreadsheets.fetch(userId, Map.of(), null), ErrorCode.ACCOUNT_NOT_CONNECTED);
    }

    @Test
    @DisplayName("토큰 서비스의 AUTHENTICATION_REQUIRED(리프레시 만료)는 바뀌지 않고 그대로 나간다")
    void tokenServiceErrorPassesThrough() {
        connected(DRIVE);
        given(tokenService.getValidAccessToken(userId))
            .willThrow(new CustomException(ErrorCode.AUTHENTICATION_REQUIRED));

        assertErrorCode(() -> spreadsheets.fetch(userId, Map.of(), null), ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("워크시트 목록 — 탭 제목을 id·name 둘 다로, 특수문자 그대로, nextCursor는 null")
    void worksheetsListsTabTitles() {
        connected(SHEETS);
        server.expect(requestTo(WORKSHEETS_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess(
                "{\"sheets\":[{\"properties\":{\"title\":\"매출\"}},"
                    + "{\"properties\":{\"title\":\"Q1/Q2 '요약' #1\"}}]}",
                MediaType.APPLICATION_JSON));

        OptionPage page = worksheets.fetch(userId, sheetInput("1Bxi_-9"), "ignored-cursor");

        assertThat(page.items()).containsExactly(
            new OptionItem("매출", "매출"),
            new OptionItem("Q1/Q2 '요약' #1", "Q1/Q2 '요약' #1"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("워크시트 목록은 drive scope만으로도 조회된다")
    void worksheetsAcceptDriveScope() {
        connected(DRIVE);
        server.expect(requestTo(WORKSHEETS_URL))
            .andRespond(withSuccess("{\"sheets\":[]}", MediaType.APPLICATION_JSON));

        assertThat(worksheets.fetch(userId, sheetInput("1Bxi_-9"), null).items()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"{{nodes.node-1.output.sheetId}}", "../files", "a/b", "id?x=1",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    @DisplayName("spreadsheet_id가 없거나 형식이 틀리면 계정·토큰·Google 조회 없이 INVALID_INPUT")
    void worksheetsRejectsMalformedSpreadsheetId(String spreadsheetId) {
        assertErrorCode(() -> worksheets.fetch(userId, sheetInput(spreadsheetId), null), ErrorCode.INVALID_INPUT);
        verify(accounts, never()).findByUserIdAndProvider(any(), any());
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @ParameterizedTest
    @CsvSource({
        "400, INVALID_INPUT",
        "401, AUTHENTICATION_REQUIRED",
        "403, GOOGLE_RESOURCE_NOT_FOUND",
        "404, GOOGLE_RESOURCE_NOT_FOUND",
        "429, GOOGLE_API_UNAVAILABLE",
        "500, GOOGLE_API_UNAVAILABLE",
        "503, GOOGLE_API_UNAVAILABLE"
    })
    @DisplayName("Google 응답 상태를 ErrorCode로 옮긴다")
    void googleErrorMapsToErrorCode(int status, ErrorCode expected) {
        connected(SHEETS);
        server.expect(requestTo(WORKSHEETS_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertErrorCode(() -> worksheets.fetch(userId, sheetInput("1Bxi_-9"), null), expected);
    }

    @Test
    @DisplayName("403 본문에 Google 오류 reason이 있어도 매핑은 그대로 GOOGLE_RESOURCE_NOT_FOUND")
    void forbiddenWithReasonBodyKeepsMapping() {
        connected(SHEETS);
        server.expect(requestTo(WORKSHEETS_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}"));

        assertErrorCode(() -> worksheets.fetch(userId, sheetInput("1Bxi_-9"), null),
            ErrorCode.GOOGLE_RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("Google 타임아웃은 GOOGLE_API_UNAVAILABLE")
    void timeoutMapsToUnavailable() {
        connected(SHEETS);
        server.expect(requestTo(WORKSHEETS_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertErrorCode(() -> worksheets.fetch(userId, sheetInput("1Bxi_-9"), null), ErrorCode.GOOGLE_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("캘린더 목록 — calendarList 250개씩, summaryOverride가 있으면 그 이름, 읽기 전용 캘린더도 포함")
    void calendarsListsCalendarList() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess("""
                {"nextPageToken":"c-2","items":[
                  {"id":"me@example.com","summary":"me@example.com","summaryOverride":"내 일정","accessRole":"owner"},
                  {"id":"ko.south_korea#holiday@group.v.calendar.google.com","summary":"대한민국의 휴일",
                   "summaryOverride":"","accessRole":"reader"}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = calendars.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("me@example.com", "내 일정"),
            new OptionItem("ko.south_korea#holiday@group.v.calendar.google.com", "대한민국의 휴일"));
        assertThat(page.nextCursor()).isEqualTo("c-2");
    }

    @Test
    @DisplayName("캘린더 목록 — cursor는 pageToken으로, 마지막 페이지면 nextCursor null")
    void calendarsPassesCursor() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250&pageToken=c-2"))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(calendars.fetch(userId, Map.of(), "c-2").nextCursor()).isNull();
    }

    @Test
    @DisplayName("calendar scope가 없으면 캘린더·일정 둘 다 토큰·Google 호출 없이 GOOGLE_SCOPE_REQUIRED")
    void calendarSourcesRequireCalendarScope() {
        connected("openid " + SHEETS + " " + DRIVE);

        assertErrorCode(() -> calendars.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        assertErrorCode(() -> events.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("일정 목록 — 지금 이후·회차별·시작순 50개, 표시명은 제목 · 시작(시각/종일/제목 없음)")
    void eventsListsUpcomingWithDisplayNames() {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andExpect(requestTo(containsString("timeMin=20")))
            .andExpect(requestTo(containsString("singleEvents=true")))
            .andExpect(requestTo(containsString("orderBy=startTime")))
            .andExpect(requestTo(containsString("maxResults=50")))
            .andExpect(requestTo(not(containsString("pageToken"))))
            .andRespond(withSuccess("""
                {"nextPageToken":"e-2","items":[
                  {"id":"evt1","summary":"주간 회의","start":{"dateTime":"2026-10-07T14:00:00+09:00"}},
                  {"id":"evt2","summary":"창립기념일","start":{"date":"2026-10-08"}},
                  {"id":"evt3","summary":"  ","start":{"dateTime":"2026-10-09T09:30:00Z"}}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = events.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("evt1", "주간 회의 · 2026-10-07 14:00"),
            new OptionItem("evt2", "창립기념일 · 2026-10-08"),
            new OptionItem("evt3", "(제목 없음) · 2026-10-09 09:30"));
        assertThat(page.nextCursor()).isEqualTo("e-2");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("일정 목록 — calendar_id가 없거나 공백이면 primary(도구 기본값과 같은 의미)")
    void eventsDefaultToPrimary(String calendarId) {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        events.fetch(userId, calendarInput(calendarId), null);
    }

    @ParameterizedTest
    @CsvSource({
        "'ko.south_korea#holiday@group.v.calendar.google.com', 'ko.south_korea%23holiday%40group.v.calendar.google.com'",
        "'a/b', 'a%2Fb'",
        "'x?y=1', 'x%3Fy%3D1'"
    })
    @DisplayName("calendar_id는 경로 한 세그먼트로 엄격 인코딩된다 — #가 fragment로, /·?가 경로·쿼리로 새지 않는다")
    void eventsEncodeCalendarIdStrictly(String calendarId, String encoded) {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + encoded + "/events?")))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        events.fetch(userId, calendarInput(calendarId), null);
    }

    @Test
    @DisplayName("일정 cursor에 &·=가 섞여도 쿼리 파라미터로 새지 않는다")
    void eventsCursorIsStrictlyEncoded() {
        connected(CALENDAR);
        server.expect(requestTo(containsString("pageToken=a%26q%3Dx")))
            .andExpect(requestTo(not(containsString("&q=x"))))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        events.fetch(userId, Map.of(), "a&q=x");
    }

    @Test
    @DisplayName("파일 목록 — agent google_drive_read가 읽는 형식만, Drive 공용 조회 옵션 그대로")
    void filesListsReadableFiles() {
        connected(DRIVE);
        server.expect(requestTo(startsWith("https://www.googleapis.com/drive/v3/files?")))
            .andExpect(driveQuery("trashed=false and ("
                + "mimeType='application/vnd.google-apps.document'"
                + " or mimeType='application/vnd.google-apps.spreadsheet'"
                + " or mimeType contains 'text/'"
                + " or mimeType='application/json'"
                + " or mimeType='application/xml'"
                + " or mimeType='application/javascript'"
                + " or mimeType='application/x-yaml')"))
            .andExpect(requestTo(containsString("orderBy=modifiedTime%20desc")))
            .andExpect(requestTo(containsString("supportsAllDrives=true")))
            .andRespond(withSuccess("{\"nextPageToken\":\"f-2\",\"files\":[{\"id\":\"f1\",\"name\":\"회의록\"}]}",
                MediaType.APPLICATION_JSON));

        OptionPage page = files.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(new OptionItem("f1", "회의록"));
        assertThat(page.nextCursor()).isEqualTo("f-2");
    }

    @Test
    @DisplayName("폴더 목록 — 폴더만, 휴지통 제외, cursor는 pageToken으로")
    void foldersListsFolders() {
        connected(DRIVE);
        server.expect(requestTo(startsWith("https://www.googleapis.com/drive/v3/files?")))
            .andExpect(driveQuery("mimeType='application/vnd.google-apps.folder' and trashed=false"))
            .andExpect(requestTo(containsString("pageToken=f-2")))
            .andRespond(withSuccess("{\"files\":[{\"id\":\"d1\",\"name\":\"보고서\"}]}", MediaType.APPLICATION_JSON));

        OptionPage page = folders.fetch(userId, Map.of(), "f-2");

        assertThat(page.items()).containsExactly(new OptionItem("d1", "보고서"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("spreadsheets scope만 있으면 파일·폴더는 GOOGLE_SCOPE_REQUIRED — drive가 필요하다")
    void filesRequireDriveScope() {
        connected("openid " + SHEETS);

        assertErrorCode(() -> files.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        assertErrorCode(() -> folders.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
    }

    @Test
    @DisplayName("공급원 키는 agent FIELD_META의 optionsSource와 1:1 계약")
    void keysMatchAgentOptionsSource() {
        assertThat(List.of(spreadsheets.key(), worksheets.key(), calendars.key(), events.key(),
                files.key(), folders.key()))
            .containsExactly("google.spreadsheets", "google.worksheets", "google.calendars", "google.events",
                "google.files", "google.folders");
    }
}
