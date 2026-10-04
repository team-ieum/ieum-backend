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
import org.springframework.web.client.RestTemplate;

/**
 * 드롭다운 공급원 2종의 Google 호출 모양과 오류 매핑을 고정한다 (IEUM-BE-71).
 *
 * <p>요청 없이 끝나야 하는 케이스(권한 없음·형식 오류)는 기대 요청을 등록하지 않는다 —
 * 요청이 나가면 {@code MockRestServiceServer}가 AssertionError를 던져 테스트가 깨진다.
 */
class GoogleOptionSourcesTest {

    private static final String DRIVE = "https://www.googleapis.com/auth/drive";
    private static final String SHEETS = "https://www.googleapis.com/auth/spreadsheets";
    private static final String WORKSHEETS_URL =
        "https://sheets.googleapis.com/v4/spreadsheets/1Bxi_-9?fields=sheets.properties.title";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GoogleTokenService tokenService = mock(GoogleTokenService.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final GoogleApiReader reader = new GoogleApiReader(tokenService, accounts, restTemplate);
    private final GoogleSpreadsheetsOptionSource spreadsheets = new GoogleSpreadsheetsOptionSource(reader);
    private final GoogleWorksheetsOptionSource worksheets = new GoogleWorksheetsOptionSource(reader);
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
}
