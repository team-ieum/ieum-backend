package com.ieum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 봇 토큰 조회·갱신 분기와 Slack 거절 분류를 고정한다(spec §5). 리포지토리는 목, AES·HTTP 호출 모양은 실제다.
 * 요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다(NotionOptionSourcesTest 방식) — 요청이 나가면 깨진다.
 */
class SlackTokenServiceTest {

    private static final String REFRESH_URL = "https://slack.com/api/oauth.v2.access";
    private static final String ROTATED = """
        {"ok":true,"access_token":"xoxe.xoxb-new","refresh_token":"xoxe-1-new","expires_in":43200,"token_type":"bot"}""";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final SlackUserLinkRepository linkRepository = mock(SlackUserLinkRepository.class);
    private final SlackInstallationRepository installationRepository = mock(SlackInstallationRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final AesEncryptor aes = aes();
    private final UUID userId = UUID.randomUUID();
    private SlackTokenService service;

    @BeforeEach
    void setUp() {
        service = new SlackTokenService(linkRepository, installationRepository, aes, new SlackApiClient(restTemplate),
            entityManager);
        ReflectionTestUtils.setField(service, "clientId", "cid");
        ReflectionTestUtils.setField(service, "clientSecret", "csecret");
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private static AesEncryptor aes() {
        AesEncryptor aes = new AesEncryptor();
        ReflectionTestUtils.setField(aes, "secretKey", "ieum-test-secret-key-32bytes-ok!");
        aes.validateKey();
        return aes;
    }

    /** 사용자에게 링크된 설치. expiresAt이 null이면 rotation 꺼짐, refreshToken은 평문(null이면 저장 안 함) */
    private SlackInstallation linked(LocalDateTime expiresAt, String refreshToken) {
        SlackInstallation installation = new SlackInstallation("T1");
        installation.applyInstall(aes.encrypt("xoxe.xoxb-old"),
            refreshToken == null ? null : aes.encrypt(refreshToken), expiresAt, "chat:write", "팀", "UBOT", "A1");
        given(linkRepository.findByUserId(userId)).willReturn(Optional.of(new SlackUserLink(userId, installation, "U1")));
        return installation;
    }

    private void expectRefresh(org.springframework.test.web.client.ResponseCreator response) {
        server.expect(requestTo(REFRESH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist("Authorization"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formDataContains(Map.of(
                "grant_type", "refresh_token", "refresh_token", "xoxe-1-old",
                "client_id", "cid", "client_secret", "csecret")))
            .andRespond(response);
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("링크가 없으면 HTTP 호출 없이 SLACK_NOT_CONNECTED")
    void notConnected() {
        given(linkRepository.findByUserId(userId)).willReturn(Optional.empty());

        assertErrorCode(() -> service.getValidBotToken(userId), ErrorCode.SLACK_NOT_CONNECTED);
    }

    @Test
    @DisplayName("만료 시각이 없으면(rotation 꺼짐) 저장 토큰을 복호화해 그대로 준다")
    void noExpiryReturnsStoredToken() {
        linked(null, null);

        assertThat(service.getValidBotToken(userId)).isEqualTo("xoxe.xoxb-old");
    }

    @Test
    @DisplayName("만료까지 5분 넘게 남았으면 교환하지 않는다")
    void freshTokenIsNotRefreshed() {
        linked(LocalDateTime.now().plusMinutes(10), "xoxe-1-old");

        assertThat(service.getValidBotToken(userId)).isEqualTo("xoxe.xoxb-old");
        verify(installationRepository, never()).save(any());
    }

    @ParameterizedTest(name = "만료까지 {0}분")
    @ValueSource(longs = {5, 3, 0, -60})
    @DisplayName("5분 이하·이미 만료 → refresh 교환 1회, 새 access·refresh·만료를 저장하고 새 access를 준다")
    void expiringTokenIsRotated(long minutesLeft) {
        SlackInstallation installation = linked(LocalDateTime.now().plusMinutes(minutesLeft), "xoxe-1-old");
        expectRefresh(withSuccess(ROTATED, MediaType.APPLICATION_JSON));

        assertThat(service.getValidBotToken(userId)).isEqualTo("xoxe.xoxb-new");

        assertThat(aes.decrypt(installation.getBotToken())).isEqualTo("xoxe.xoxb-new");
        assertThat(aes.decrypt(installation.getRefreshToken())).isEqualTo("xoxe-1-new");
        assertThat(installation.getTokenExpiresAt()).isAfter(LocalDateTime.now().plusHours(11));
        verify(installationRepository).save(installation);
        verify(entityManager).refresh(installation, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("락을 잡고 다시 읽은 설치가 다른 요청에 의해 이미 갱신됐으면 교환하지 않고 그 토큰을 준다")
    void skipsExchangeWhenAnotherRequestRotated() {
        SlackInstallation installation = linked(LocalDateTime.now().plusMinutes(1), "xoxe-1-old");
        doAnswer(invocation -> {
            installation.rotate(aes.encrypt("xoxe.xoxb-other"), aes.encrypt("xoxe-1-other"), LocalDateTime.now().plusHours(12));
            return null;
        }).when(entityManager).refresh(installation, LockModeType.PESSIMISTIC_WRITE);

        assertThat(service.getValidBotToken(userId)).isEqualTo("xoxe.xoxb-other");
        verify(installationRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_refresh_token", "token_revoked", "token_expired", "account_inactive", "invalid_auth"})
    @DisplayName("재연결로만 풀리는 거절 → AUTHENTICATION_REQUIRED('Slack을 다시 연결해 주세요'), 저장 없음")
    void reconnectErrors(String error) {
        linked(LocalDateTime.now().minusMinutes(1), "xoxe-1-old");
        expectRefresh(withSuccess("{\"ok\":false,\"error\":\"" + error + "\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.getValidBotToken(userId))
            .isInstanceOf(CustomException.class)
            .hasMessage("Slack을 다시 연결해 주세요")
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
        verify(installationRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_client_id", "bad_client_secret", "internal_error", "service_unavailable"})
    @DisplayName("설정·Slack 장애 거절 → TOKEN_REFRESH_FAILED")
    void otherErrorsAreRefreshFailures(String error) {
        linked(LocalDateTime.now().minusMinutes(1), "xoxe-1-old");
        expectRefresh(withSuccess("{\"ok\":false,\"error\":\"" + error + "\"}", MediaType.APPLICATION_JSON));

        assertErrorCode(() -> service.getValidBotToken(userId), ErrorCode.TOKEN_REFRESH_FAILED);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    @DisplayName("HTTP 429·5xx → TOKEN_REFRESH_FAILED")
    void httpFailuresAreRefreshFailures(int status) {
        linked(LocalDateTime.now().minusMinutes(1), "xoxe-1-old");
        expectRefresh(withStatus(HttpStatus.valueOf(status)));

        assertErrorCode(() -> service.getValidBotToken(userId), ErrorCode.TOKEN_REFRESH_FAILED);
    }

    @Test
    @DisplayName("네트워크 오류 → TOKEN_REFRESH_FAILED")
    void networkFailureIsRefreshFailure() {
        linked(LocalDateTime.now().minusMinutes(1), "xoxe-1-old");
        expectRefresh(withException(new SocketTimeoutException("read timed out")));

        assertErrorCode(() -> service.getValidBotToken(userId), ErrorCode.TOKEN_REFRESH_FAILED);
    }

    @Test
    @DisplayName("ok:true인데 refresh_token이 없으면 저장하지 않고 TOKEN_REFRESH_FAILED — 1회용 refresh를 잃지 않게")
    void missingRefreshTokenInResponse() {
        linked(LocalDateTime.now().minusMinutes(1), "xoxe-1-old");
        expectRefresh(withSuccess("{\"ok\":true,\"access_token\":\"xoxe.xoxb-new\"}", MediaType.APPLICATION_JSON));

        assertErrorCode(() -> service.getValidBotToken(userId), ErrorCode.TOKEN_REFRESH_FAILED);
        verify(installationRepository, never()).save(any());
    }

    @Test
    @DisplayName("exchangeRefreshToken — 새 access·refresh·expires_in을 돌려주고 아무것도 저장하지 않는다(해제가 쓴다)")
    void exchangeDoesNotPersist() {
        expectRefresh(withSuccess(ROTATED, MediaType.APPLICATION_JSON));

        assertThat(service.exchangeRefreshToken("xoxe-1-old"))
            .isEqualTo(new SlackTokenService.RefreshedTokens("xoxe.xoxb-new", "xoxe-1-new", 43200L));
        verify(installationRepository, never()).save(any());
    }

    @Test
    @DisplayName("RefreshedTokens.toString은 토큰 값을 싣지 않는다(로그·예외 메시지 유출 방지)")
    void refreshedTokensToStringHidesTokens() {
        assertThat(new SlackTokenService.RefreshedTokens("xoxe.xoxb-new", "xoxe-1-new", 43200L).toString())
            .isEqualTo("RefreshedTokens[expiresIn=43200]");
    }

    @Test
    @DisplayName("exchangeRefreshToken — 재연결 거절은 같은 분류(AUTHENTICATION_REQUIRED)")
    void exchangeClassifiesRejection() {
        expectRefresh(withSuccess("{\"ok\":false,\"error\":\"token_revoked\"}", MediaType.APPLICATION_JSON));

        assertErrorCode(() -> service.exchangeRefreshToken("xoxe-1-old"), ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("만료 시각은 있는데 저장된 refresh가 없으면 HTTP 없이 AUTHENTICATION_REQUIRED")
    void expiringWithoutStoredRefresh() {
        linked(LocalDateTime.now().minusMinutes(1), null);

        assertErrorCode(() -> service.getValidBotToken(userId), ErrorCode.AUTHENTICATION_REQUIRED);
    }
}
