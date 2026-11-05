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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.web.util.UriUtils;

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
    private static final String GMAIL = "https://www.googleapis.com/auth/gmail.modify";
    private static final String SEND_AS_URL = "https://gmail.googleapis.com/gmail/v1/users/me/settings/sendAs";
    private static final String LABELS_URL = "https://gmail.googleapis.com/gmail/v1/users/me/labels";
    private static final String SHEETS_API = "https://sheets.googleapis.com/v4/spreadsheets/1Bxi_-9";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GoogleTokenService tokenService = mock(GoogleTokenService.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final GoogleApiReader reader = new GoogleApiReader(tokenService, accounts, restTemplate);
    private final GoogleSpreadsheetsOptionSource spreadsheets = new GoogleSpreadsheetsOptionSource(reader);
    private final GoogleWorksheetsOptionSource worksheets = new GoogleWorksheetsOptionSource(reader);
    private final OptionSource calendars = new GoogleCalendarOptionSourceConfig().googleCalendarsOptionSource(reader);
    private final OptionSource writableCalendars =
        new GoogleCalendarOptionSourceConfig().googleWritableCalendarsOptionSource(reader);
    private final GoogleEventsOptionSource events = new GoogleEventsOptionSource(reader);
    private final GoogleFilesOptionSource files = new GoogleFilesOptionSource(reader);
    private final GoogleFoldersOptionSource folders = new GoogleFoldersOptionSource(reader);
    private final GmailSendAsOptionSource gmailSendAs = new GmailSendAsOptionSource(reader);
    private final GmailLabelsOptionSource gmailLabels = new GmailLabelsOptionSource(reader);
    private final GoogleSheetColumnsReader columnsReader = new GoogleSheetColumnsReader(reader);
    private final GoogleSheetColumnsOptionSource sheetColumns = new GoogleSheetColumnsOptionSource(columnsReader);
    private final GoogleSheetColumnsFieldSource sheetColumnFields = new GoogleSheetColumnsFieldSource(columnsReader);
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

    private Map<String, String> columnInput(String spreadsheetId, String sheetId) {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("spreadsheet_id", spreadsheetId);
        inputs.put("sheet_id", sheetId);
        return inputs;
    }

    private static String sheet(String idJson, String title, int columnCount) {
        return "{\"properties\":{" + idJson + "\"title\":\"" + title + "\","
            + "\"gridProperties\":{\"columnCount\":" + columnCount + "}}}";
    }

    /** 메타 → 헤더 두 요청을 순서대로 기대한다. headerJson이 null이면 헤더 요청이 나가면 안 된다 */
    private void expectSheet(String sheetsJson, String headerJson, RequestMatcher headerCheck) {
        server.expect(requestTo(startsWith(SHEETS_API + "?")))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andExpect(queryValues("fields", "sheets.properties(sheetId,title,gridProperties.columnCount)"))
            .andRespond(withSuccess("{\"sheets\":[" + sheetsJson + "]}", MediaType.APPLICATION_JSON));
        if (headerJson != null) {
            server.expect(requestTo(startsWith(SHEETS_API + "/values/")))
                .andExpect(headerCheck)
                .andRespond(withSuccess(headerJson, MediaType.APPLICATION_JSON));
        }
    }

    /** values.get 경로 마지막 조각 — 날것은 엄격 인코딩, 퍼센트 디코드하면 기대 A1 범위.
     *  URLDecoder(폼 디코딩)는 '+'를 공백으로 바꿔 '+'로 보낸 구현도 통과시키므로 UriUtils.decode를 쓴다 */
    private static RequestMatcher headerRange(String expected) {
        return request -> {
            String raw = request.getURI().getRawPath();
            String segment = raw.substring(raw.lastIndexOf('/') + 1);
            assertThat(raw).startsWith("/v4/spreadsheets/1Bxi_-9/values/");
            assertThat(UriUtils.decode(segment, StandardCharsets.UTF_8)).isEqualTo(expected);
        };
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

    /** 쿼리 파라미터 name의 디코드된 값 전부를 순서대로 비교한다 — 기대값이 없으면 파라미터가 없어야 한다 */
    private static RequestMatcher queryValues(String name, String... expected) {
        return request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build(true).getQueryParams()
            .getOrDefault(name, List.of()).stream()
            .map(v -> URLDecoder.decode(v, StandardCharsets.UTF_8))
            .toList())
            .containsExactly(expected);
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
    @DisplayName("열 드롭다운 — sheetId로 시트를 찾아 1행 헤더를 열 문자 id·'B · 이메일' 이름으로, 중간 빈 헤더는 (제목 없음)")
    void sheetColumnsListsHeadersByLetter() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":7,", "다른 시트", 26) + "," + sheet("\"sheetId\":42,", "응답", 26),
            "{\"values\":[[\"이름\",\"이메일\",\"\",\"전화\"]]}", headerRange("'응답'!1:1"));

        OptionPage page = sheetColumns.fetch(userId, columnInput("1Bxi_-9", "42"), "ignored-cursor");

        assertThat(page.items()).containsExactly(
            new OptionItem("A", "A · 이름"), new OptionItem("B", "B · 이메일"),
            new OptionItem("C", "C · (제목 없음)"), new OptionItem("D", "D · 전화"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("열 드롭다운 — 마지막 비지 않은 헤더 뒤의 빈 칸·공백뿐인 칸은 열이 아니다")
    void sheetColumnsDropTrailingBlankHeaders() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":42,", "응답", 26), "{\"values\":[[\"이름\",\"\",\"  \"]]}", request -> { });

        assertThat(sheetColumns.fetch(userId, columnInput("1Bxi_-9", "42"), null).items())
            .containsExactly(new OptionItem("A", "A · 이름"));
    }

    @ParameterizedTest
    @CsvSource({"3, C, 3", "40, Z, 26"})
    @DisplayName("헤더 행이 비면(values 없음) A부터 min(열 수, 26)까지 열 문자만")
    void sheetColumnsAllBlankUseColumnCountUpToZ(int columnCount, String last, int size) {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":42,", "응답", columnCount), "{\"range\":\"'응답'!1:1\"}", request -> { });

        List<OptionItem> items = sheetColumns.fetch(userId, columnInput("1Bxi_-9", "42"), null).items();

        assertThat(items).hasSize(size).first().isEqualTo(new OptionItem("A", "A"));
        assertThat(items.get(size - 1)).isEqualTo(new OptionItem(last, last));
    }

    @Test
    @DisplayName("헤더 행이 빈 문자열·공백뿐이어도(values는 있음) 전부 빈 헤더로 본다")
    void sheetColumnsWhitespaceOnlyHeaderRowIsAllBlank() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":42,", "응답", 3), "{\"values\":[[\"\",\"  \"]]}", request -> { });

        assertThat(sheetColumns.fetch(userId, columnInput("1Bxi_-9", "42"), null).items()).containsExactly(
            new OptionItem("A", "A"), new OptionItem("B", "B"), new OptionItem("C", "C"));
    }

    @Test
    @DisplayName("헤더가 있으면 26개 상한이 없다 — 27번째 헤더는 AA")
    void sheetColumnsBeyondZ() {
        connected(SHEETS);
        String headers = IntStream.rangeClosed(1, 27).mapToObj(i -> "\"h" + i + "\"")
            .collect(Collectors.joining(","));
        expectSheet(sheet("\"sheetId\":42,", "응답", 30), "{\"values\":[[" + headers + "]]}", request -> { });

        List<OptionItem> items = sheetColumns.fetch(userId, columnInput("1Bxi_-9", "42"), null).items();

        assertThat(items).hasSize(27).last().isEqualTo(new OptionItem("AA", "AA · h27"));
    }

    @ParameterizedTest
    @CsvSource({"0, A", "25, Z", "26, AA", "27, AB", "51, AZ", "52, BA", "701, ZZ", "702, AAA"})
    @DisplayName("열 문자는 A…Z 다음 AA(bijective base-26)")
    void columnLetterIsBijectiveBase26(int index, String letter) {
        assertThat(GoogleSheetColumnsReader.columnLetter(index)).isEqualTo(letter);
    }

    @Test
    @DisplayName("sheet_id \"0\"은 정상 값 — 응답에서 sheetId가 생략된 첫 시트를 찾는다 (drive scope만으로도)")
    void sheetColumnsFirstSheetIdZero() {
        connected(DRIVE);
        expectSheet(sheet("", "Sheet1", 26) + "," + sheet("\"sheetId\":5,", "Other", 26),
            "{\"values\":[[\"이름\"]]}", headerRange("'Sheet1'!1:1"));

        assertThat(sheetColumns.fetch(userId, columnInput("1Bxi_-9", "0"), null).items())
            .containsExactly(new OptionItem("A", "A · 이름"));
    }

    @Test
    @DisplayName("시트 제목의 '·/·#·?·공백은 A1 이스케이프('')와 경로 엄격 인코딩(%2F·%20)으로 보낸다")
    void sheetColumnsEscapesTitleAndEncodesRange() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":3,", "Q1/Q2 '요약' #1?", 26), "{\"values\":[[\"이름\"]]}",
            request -> {
                assertThat(request.getURI().getRawPath()).contains("%2F").doesNotContain("#", "?", "+");
                headerRange("'Q1/Q2 ''요약'' #1?'!1:1").match(request);
            });

        assertThat(sheetColumns.fetch(userId, columnInput("1Bxi_-9", "3"), null).items()).hasSize(1);
    }

    @Test
    @DisplayName("그 파일에 없는 sheetId는 헤더를 읽지 않고 GOOGLE_RESOURCE_NOT_FOUND")
    void sheetColumnsUnknownSheetIdIsNotFound() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":1,", "응답", 26), null, null);

        assertErrorCode(() -> sheetColumns.fetch(userId, columnInput("1Bxi_-9", "9"), null),
            ErrorCode.GOOGLE_RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @CsvSource({
        ", 0", "'  ', 0", "'{{nodes.a.output.id}}', 0", "../x, 0",
        "1Bxi_-9, ", "1Bxi_-9, ''", "1Bxi_-9, '{{nodes.a.output.sheet}}'", "1Bxi_-9, -1",
        "1Bxi_-9, 1.5", "1Bxi_-9, 매출", "1Bxi_-9, 12345678901"
    })
    @DisplayName("spreadsheet_id·sheet_id가 비었거나 참조식·형식 오류면 계정·토큰·Google 조회 없이 INVALID_INPUT")
    void sheetColumnsRejectBadInputBeforeTokenLookup(String spreadsheetId, String sheetId) {
        assertErrorCode(() -> sheetColumns.fetch(userId, columnInput(spreadsheetId, sheetId), null),
            ErrorCode.INVALID_INPUT);
        verify(accounts, never()).findByUserIdAndProvider(any(), any());
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("열 칸 목록 — 열마다 {key:열 문자, title:'헤더 (B)', type:string, required:false}, 빈 헤더는 '(제목 없음) (C)'")
    void sheetColumnFieldsShape() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":42,", "응답", 26),
            "{\"values\":[[\"이름\",\"이메일\",\"\",\"전화\"]]}", headerRange("'응답'!1:1"));

        assertThat(sheetColumnFields.fetch(userId, columnInput("1Bxi_-9", "42"))).containsExactly(
            Map.of("key", "A", "title", "이름 (A)", "type", "string", "required", false),
            Map.of("key", "B", "title", "이메일 (B)", "type", "string", "required", false),
            Map.of("key", "C", "title", "(제목 없음) (C)", "type", "string", "required", false),
            Map.of("key", "D", "title", "전화 (D)", "type", "string", "required", false));
    }

    @Test
    @DisplayName("헤더 행이 비면 칸 title도 열 문자만")
    void sheetColumnFieldsAllBlankLetterOnly() {
        connected(SHEETS);
        expectSheet(sheet("\"sheetId\":42,", "응답", 2), "{}", request -> { });

        assertThat(sheetColumnFields.fetch(userId, columnInput("1Bxi_-9", "42"))).containsExactly(
            Map.of("key", "A", "title", "A", "type", "string", "required", false),
            Map.of("key", "B", "title", "B", "type", "string", "required", false));
    }

    @Test
    @DisplayName("칸 목록도 참조식 sheet_id는 토큰 조회 없이 INVALID_INPUT")
    void sheetColumnFieldsRejectReferenceSheetId() {
        assertErrorCode(() -> sheetColumnFields.fetch(userId, columnInput("1Bxi_-9", "{{nodes.a.output.sheet}}")),
            ErrorCode.INVALID_INPUT);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("캘린더 목록 — calendarList 250개씩, summaryOverride가 있으면 그 이름, 읽기 전용 캘린더도 포함")
    void calendarsListsCalendarList() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250&minAccessRole=reader"))
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
            new OptionItem("ko.south_korea#holiday@group.v.calendar.google.com", "대한민국의 휴일 (읽기 전용)"));
        assertThat(page.nextCursor()).isEqualTo("c-2");
    }

    @Test
    @DisplayName("캘린더 목록 — cursor는 pageToken으로, 마지막 페이지면 nextCursor null")
    void calendarsPassesCursor() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250&minAccessRole=reader&pageToken=c-2"))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(calendars.fetch(userId, Map.of(), "c-2").nextCursor()).isNull();
    }

    @Test
    @DisplayName("쓰기 캘린더 목록 — reader까지 받아 accessRole owner·writer·writerWithoutPrivateAccess만, 꼬리표 없음")
    void writableCalendarsKeepWritableRolesOnly() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250&minAccessRole=reader"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess("""
                {"nextPageToken":"c-2","items":[
                  {"id":"me@example.com","summary":"me@example.com","summaryOverride":"내 일정","accessRole":"owner"},
                  {"id":"team@group.calendar.google.com","summary":"팀 일정","accessRole":"writer"},
                  {"id":"shared@group.calendar.google.com","summary":"공유 일정","accessRole":"writerWithoutPrivateAccess"},
                  {"id":"ko.south_korea#holiday@group.v.calendar.google.com","summary":"대한민국의 휴일",
                   "summaryOverride":"","accessRole":"reader"}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = writableCalendars.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("me@example.com", "내 일정"),
            new OptionItem("team@group.calendar.google.com", "팀 일정"),
            new OptionItem("shared@group.calendar.google.com", "공유 일정"));
        assertThat(page.nextCursor()).isEqualTo("c-2");
    }

    @Test
    @DisplayName("쓰기 캘린더 목록 — accessRole이 없거나 모르는 값(freeBusyReader·대소문자 다름)이면 뺀다")
    void writableCalendarsDropUnknownRoles() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250&minAccessRole=reader"))
            .andRespond(withSuccess("""
                {"items":[
                  {"id":"a","summary":"A","accessRole":"freeBusyReader"},
                  {"id":"b","summary":"B"},
                  {"id":"c","summary":"C","accessRole":"OWNER"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(writableCalendars.fetch(userId, Map.of(), null).items()).isEmpty();
    }

    @Test
    @DisplayName("쓰기 캘린더 목록 — 한 페이지가 전부 걸러져도 nextCursor를 넘긴다, cursor는 pageToken으로")
    void writableCalendarsKeepCursorWhenPageFiltered() {
        connected(CALENDAR);
        server.expect(requestTo(CALENDAR_LIST_URL + "?maxResults=250&minAccessRole=reader&pageToken=c-2"))
            .andRespond(withSuccess("""
                {"nextPageToken":"c-3","items":[{"id":"h","summary":"휴일","accessRole":"reader"}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = writableCalendars.fetch(userId, Map.of(), "c-2");

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo("c-3");
    }

    @Test
    @DisplayName("calendar scope가 없으면 캘린더·쓰기 캘린더·일정 모두 토큰·Google 호출 없이 GOOGLE_SCOPE_REQUIRED")
    void calendarSourcesRequireCalendarScope() {
        connected("openid " + SHEETS + " " + DRIVE);

        assertErrorCode(() -> calendars.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        assertErrorCode(() -> writableCalendars.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        assertErrorCode(() -> events.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("일정 목록 — 지금 이후·회차별·시작순 20개, 표시명은 제목 · 시작(시각/종일/제목 없음)")
    void eventsListsUpcomingWithDisplayNames() {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            // 소수 초 없이 — JDK·OS에 따라 Instant.toString()이 마이크로·나노초까지 찍는다
            .andExpect(request -> assertThat(URLDecoder.decode(
                UriComponentsBuilder.fromUri(request.getURI()).build(true).getQueryParams().getFirst("timeMin"),
                StandardCharsets.UTF_8)).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"))
            .andExpect(requestTo(containsString("singleEvents=true")))
            .andExpect(requestTo(containsString("orderBy=startTime")))
            .andExpect(requestTo(containsString("maxResults=20")))
            .andExpect(queryValues("q"))
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
        "'x?y=1', 'x%3Fy%3D1'",
        "'ko.south_korea%23holiday%40group.v.calendar.google.com', 'ko.south_korea%23holiday%40group.v.calendar.google.com'",
        "'  a@b.com  ', 'a%40b.com'"
    })
    @DisplayName("calendar_id는 strip·한 번 디코드 후 경로 한 세그먼트로 엄격 인코딩된다 — 이중 인코딩 없음, #·/·?가 새지 않는다")
    void eventsEncodeCalendarIdStrictly(String calendarId, String encoded) {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + encoded + "/events?")))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        events.fetch(userId, calendarInput(calendarId), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{nodes.x.output.y}}", "100%", "%zz"})
    @DisplayName("calendar_id가 변수 참조식이거나 %시퀀스가 깨졌으면 계정·토큰·Google 조회 없이 INVALID_INPUT")
    void eventsRejectUnresolvableCalendarId(String calendarId) {
        assertErrorCode(() -> events.fetch(userId, calendarInput(calendarId), null), ErrorCode.INVALID_INPUT);
        verify(accounts, never()).findByUserIdAndProvider(any(), any());
        verify(tokenService, never()).getValidAccessToken(any());
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
    @DisplayName("일정 목록 — q가 있으면 앞뒤 공백을 떼어 전달하고, 검색해도 timeMin=지금·20개는 그대로")
    void eventsPassSearchQuery() {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andExpect(queryValues("q", "주간 회의"))
            .andExpect(request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                .getQueryParams().get("timeMin")).singleElement().asString()
                .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d(:|%3A)\\d\\d(:|%3A)\\d\\dZ"))
            .andExpect(requestTo(containsString("singleEvents=true")))
            .andExpect(requestTo(containsString("orderBy=startTime")))
            .andExpect(requestTo(containsString("maxResults=20")))
            .andRespond(withSuccess("""
                {"items":[{"id":"evt1","summary":"주간 회의","start":{"dateTime":"2026-10-21T14:00:00+09:00"}}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = events.fetch(userId, Map.of("q", "  주간 회의  "), null);

        assertThat(page.items()).containsExactly(new OptionItem("evt1", "주간 회의 · 2026-10-21 14:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("일정 목록 — 빈·공백뿐인 q는 없는 것으로 보고 q 파라미터를 붙이지 않는다")
    void eventsIgnoreBlankQuery(String q) {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andExpect(queryValues("q"))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        events.fetch(userId, Map.of("q", q), null);
    }

    @Test
    @DisplayName("검색어에 &·=·#가 섞여도 q 한 값으로만 가고 timeMin·pageToken을 덮지 않는다")
    void eventsSearchQueryIsStrictlyEncoded() {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andExpect(queryValues("q", "{x}a&timeMin=2000-01-01T00:00:00Z&pageToken=x#y"))
            .andExpect(request -> assertThat(request.getURI().getRawQuery())
                .contains("q=%7Bx%7Da%26timeMin%3D2000-01-01T00%3A00%3A00Z%26pageToken%3Dx%23y"))
            .andExpect(queryValues("pageToken"))
            .andExpect(request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build(true)
                .getQueryParams().get("timeMin")).singleElement().asString().doesNotStartWith("2000"))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        events.fetch(userId, Map.of("q", "{x}a&timeMin=2000-01-01T00:00:00Z&pageToken=x#y"), null);
    }

    @Test
    @DisplayName("검색 결과 다음 페이지도 q를 다시 보낸다 — pageToken은 같은 쿼리로만 유효하다")
    void eventsSearchKeepsQueryOnNextPage() {
        connected(CALENDAR);
        server.expect(requestTo(startsWith(EVENTS_PREFIX + "primary/events?")))
            .andExpect(queryValues("q", "회의"))
            .andExpect(queryValues("pageToken", "e-2"))
            .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(events.fetch(userId, Map.of("q", "회의"), "e-2").nextCursor()).isNull();
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
    @DisplayName("공급원 키는 카탈로그 optionsSource와 1:1 계약")
    void keysMatchAgentOptionsSource() {
        assertThat(List.of(spreadsheets.key(), worksheets.key(), calendars.key(), writableCalendars.key(),
                events.key(), files.key(), folders.key(), gmailSendAs.key(), gmailLabels.key(), sheetColumns.key()))
            .containsExactly("google.spreadsheets", "google.worksheets", "google.calendars",
                "google.writable_calendars", "google.events", "google.files", "google.folders",
                "google.gmail_send_as", "google.gmail_labels", "google.sheet_columns");
    }

    @Test
    @DisplayName("칸 목록 공급원 키는 카탈로그 fieldsSource와 1:1 계약")
    void fieldSourceKeyMatchesCatalog() {
        assertThat(sheetColumnFields.key()).isEqualTo("google.sheet_columns");
    }

    @Test
    @DisplayName("보낸 사람 — accepted·기본 주소만, pending 제외, 이름은 'displayName <주소>', isDefault가 맨 앞, nextCursor null")
    void gmailSendAsListsVerifiedAliasesDefaultFirst() {
        connected("openid " + GMAIL);
        server.expect(requestTo(SEND_AS_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess("""
                {"sendAs":[
                  {"sendAsEmail":"me@gmail.com","displayName":"윤도훈","isPrimary":true,"isDefault":false},
                  {"sendAsEmail":"wait@ex.com","displayName":"대기","verificationStatus":"pending"},
                  {"sendAsEmail":"work@ex.com","displayName":"회사","verificationStatus":"accepted","isDefault":true}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = gmailSendAs.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("work@ex.com", "회사 <work@ex.com>"),
            new OptionItem("me@gmail.com", "윤도훈 <me@gmail.com>"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("보낸 사람 — displayName이 없거나 빈 문자열이면 주소만")
    void gmailSendAsBlankDisplayNameIsAddressOnly() {
        connected(GMAIL);
        server.expect(requestTo(SEND_AS_URL)).andRespond(withSuccess("""
            {"sendAs":[{"sendAsEmail":"a@ex.com","isPrimary":true},
              {"sendAsEmail":"b@ex.com","displayName":"","verificationStatus":"verificationStatusUnspecified"}]}
            """, MediaType.APPLICATION_JSON));

        OptionPage page = gmailSendAs.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("a@ex.com", "a@ex.com"), new OptionItem("b@ex.com", "b@ex.com"));
    }

    @Test
    @DisplayName("보낸 사람 — isDefault가 하나도 없으면 API 순서 그대로")
    void gmailSendAsWithoutDefaultKeepsApiOrder() {
        connected(GMAIL);
        server.expect(requestTo(SEND_AS_URL)).andRespond(withSuccess("""
            {"sendAs":[{"sendAsEmail":"c@ex.com","verificationStatus":"accepted"},
              {"sendAsEmail":"a@ex.com","isPrimary":true},
              {"sendAsEmail":"b@ex.com","verificationStatus":"accepted"}]}
            """, MediaType.APPLICATION_JSON));

        OptionPage page = gmailSendAs.fetch(userId, Map.of(), null);

        assertThat(page.items()).extracting(OptionItem::id).containsExactly("c@ex.com", "a@ex.com", "b@ex.com");
    }

    @Test
    @DisplayName("보낸 사람 — cursor가 와도 pageToken 없이 같은 URL, 빈 본문이면 items=[]·nextCursor null")
    void gmailSendAsIgnoresCursorAndEmptyBody() {
        connected(GMAIL);
        server.expect(requestTo(SEND_AS_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        OptionPage page = gmailSendAs.fetch(userId, Map.of(), "x&pageToken=y");

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("gmail.readonly만 있어도 보낸 사람 목록이 조회된다 — 트리거와 같은 허용 scope")
    void gmailSendAsAcceptsReadonlyScope() {
        connected("openid https://www.googleapis.com/auth/gmail.readonly");
        server.expect(requestTo(SEND_AS_URL)).andRespond(withSuccess("{\"sendAs\":[]}", MediaType.APPLICATION_JSON));

        assertThat(gmailSendAs.fetch(userId, Map.of(), null).items()).isEmpty();
    }

    @Test
    @DisplayName("gmail.readonly만 있어도 라벨 목록이 조회된다 — 트리거와 같은 허용 scope")
    void gmailLabelsAcceptsReadonlyScope() {
        connected("openid https://www.googleapis.com/auth/gmail.readonly");
        server.expect(requestTo(LABELS_URL)).andRespond(withSuccess("{\"labels\":[]}", MediaType.APPLICATION_JSON));

        assertThat(gmailLabels.fetch(userId, Map.of(), null).items()).isEmpty();
    }

    @Test
    @DisplayName("gmail scope가 없으면 보낸 사람·라벨 모두 토큰·Google 호출 없이 GOOGLE_SCOPE_REQUIRED")
    void gmailSourcesRequireGmailScope() {
        connected("openid " + SHEETS + " " + DRIVE + " " + CALENDAR);

        assertErrorCode(() -> gmailSendAs.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        assertErrorCode(() -> gmailLabels.fetch(userId, Map.of(), null), ErrorCode.GOOGLE_SCOPE_REQUIRED);
        verify(tokenService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("라벨 — SENT·DRAFT·모르는 시스템 라벨 제외, 시스템 라벨은 한국어 이름, 사용자 라벨은 그대로, API 순서, nextCursor null")
    void gmailLabelsListsAssignableLabels() {
        connected(GMAIL);
        server.expect(requestTo(LABELS_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess("""
                {"labels":[
                  {"id":"INBOX","name":"INBOX","type":"system"},
                  {"id":"SENT","name":"SENT","type":"system"},
                  {"id":"DRAFT","name":"DRAFT","type":"system"},
                  {"id":"CHAT","name":"CHAT","type":"system"},
                  {"id":"CATEGORY_PERSONAL","name":"CATEGORY_PERSONAL","type":"system"},
                  {"id":"Label_1","name":"프로젝트/IEUM","type":"user"}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = gmailLabels.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("INBOX", "받은편지함"),
            new OptionItem("CATEGORY_PERSONAL", "기본"),
            new OptionItem("Label_1", "프로젝트/IEUM"));
        assertThat(page.nextCursor()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"INBOX,받은편지함", "STARRED,별표", "IMPORTANT,중요", "UNREAD,읽지 않음", "SPAM,스팸", "TRASH,휴지통",
        "CATEGORY_PERSONAL,기본", "CATEGORY_SOCIAL,소셜", "CATEGORY_PROMOTIONS,프로모션",
        "CATEGORY_UPDATES,업데이트", "CATEGORY_FORUMS,포럼"})
    @DisplayName("라벨 — 시스템 라벨 11개의 한국어 이름")
    void gmailLabelsSystemNames(String id, String name) {
        connected(GMAIL);
        server.expect(requestTo(LABELS_URL)).andRespond(withSuccess(
            "{\"labels\":[{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"type\":\"system\"}]}",
            MediaType.APPLICATION_JSON));

        OptionPage page = gmailLabels.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(new OptionItem(id, name));
    }

    @Test
    @DisplayName("라벨 — 시스템/사용자 구분은 type으로만: 이름이 INBOX인 사용자 라벨은 그대로, type 없는 항목은 사용자 라벨")
    void gmailLabelsClassifyByTypeNotId() {
        connected(GMAIL);
        server.expect(requestTo(LABELS_URL)).andRespond(withSuccess("""
            {"labels":[{"id":"Label_7","name":"INBOX","type":"user"},{"id":"Label_8","name":"메모"}]}
            """, MediaType.APPLICATION_JSON));

        OptionPage page = gmailLabels.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("Label_7", "INBOX"), new OptionItem("Label_8", "메모"));
    }

    @Test
    @DisplayName("라벨 — cursor가 와도 pageToken 없이 같은 URL, 빈 본문이면 items=[]·nextCursor null")
    void gmailLabelsIgnoresCursorAndEmptyBody() {
        connected(GMAIL);
        server.expect(requestTo(LABELS_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        OptionPage page = gmailLabels.fetch(userId, Map.of(), "c-2");

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }
}
