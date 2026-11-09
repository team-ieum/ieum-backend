package com.ieum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** GitHub App 설치 토큰(spec 5.1) — JWT 모양, 레포 하나·pull_requests:write로 좁힌 발급, 캐시, 오류 매핑. */
class GitHubAppTokenServiceTest {

    private static final String API = "https://api.github.com";
    private static final String CLIENT_ID = "Iv1.test-client";
    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(1));
    private static final KeyPair KEYS = rsaKeyPair();
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());
    private static final String TOKEN_JSON =
        "{\"token\":\"ghs_installation\",\"expires_at\":\"" + EXPIRES_AT + "\"}";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final MovableClock clock = new MovableClock(NOW);
    private final GitHubAppTokenService service = new GitHubAppTokenService(CLIENT_ID, KEY_B64, restTemplate, clock);

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    @DisplayName("키가 비면 enabled=false, 발급은 HTTP 없이 GITHUB_BOT_NOT_CONFIGURED")
    void 키가_비면_enabled_false이고_발급은_GITHUB_BOT_NOT_CONFIGURED() {
        GitHubAppTokenService empty = new GitHubAppTokenService(CLIENT_ID, "", restTemplate, clock);

        assertThat(empty.enabled()).isFalse();
        assertThat(service.enabled()).isTrue();
        assertThatThrownBy(() -> empty.installationToken("o", "r"))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("키가 있는데 base64 아님 / PKCS#8 아님이면 생성자에서 IllegalStateException(기동 실패)")
    void 키가_깨졌으면_생성자_예외() {
        assertThatThrownBy(() -> new GitHubAppTokenService(CLIENT_ID, "not base64!!", restTemplate, clock))
            .isInstanceOf(IllegalStateException.class);
        String notPkcs8 = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new GitHubAppTokenService(CLIENT_ID, notPkcs8, restTemplate, clock))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("App JWT — RS256, iss=clientId, iat=now−60s, exp=now+9m, 공개 키로 서명 검증")
    void JWT_클레임() {
        AtomicReference<String> auth = new AtomicReference<>();
        server.expect(requestTo(API + "/repos/o/r/installation"))
            .andExpect(req -> auth.set(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
            .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/app/installations/42/access_tokens"))
            .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        service.installationToken("o", "r");

        assertThat(auth.get()).startsWith("Bearer ");
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(KEYS.getPublic())
            .clock(() -> Date.from(NOW))
            .build()
            .parseSignedClaims(auth.get().substring("Bearer ".length()));
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(jws.getPayload().getIssuer()).isEqualTo(CLIENT_ID);
        assertThat(jws.getPayload().getIssuedAt().toInstant()).isEqualTo(NOW.minusSeconds(60));
        assertThat(jws.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(9)));
    }

    @Test
    @DisplayName("발급 — GET 설치(Bearer JWT) → id 42 → POST access_tokens 본문은 레포 하나·pull_requests:write")
    void 발급_요청_모양() {
        server.expect(requestTo(API + "/repos/o/r/installation"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer ")))
            .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
            .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/app/installations/42/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer ")))
            .andExpect(content().json("{\"repositories\":[\"r\"],\"permissions\":{\"pull_requests\":\"write\"}}", true))
            .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        assertThat(service.installationToken("o", "r")).isEqualTo("ghs_installation");
    }

    @Test
    @DisplayName("캐시 — 같은 레포는 재사용, expires_at−4분(5분 전 경계 안)이면 다시 발급")
    void 캐시_만료_5분_전까지_재사용() {
        expectIssue("o", "r");
        expectIssue("o", "r");

        service.installationToken("o", "r");
        service.installationToken("o", "r");
        clock.set(EXPIRES_AT.minus(Duration.ofMinutes(6)));
        service.installationToken("o", "r");
        clock.set(EXPIRES_AT.minus(Duration.ofMinutes(4)));
        service.installationToken("o", "r");
    }

    @Test
    @DisplayName("캐시 키는 owner/repo 대소문자 무시 — Hodoon/R과 hodoon/r은 POST 1회")
    void 캐시_키는_대소문자_무시() {
        expectIssue("Hodoon", "R");

        assertThat(service.installationToken("Hodoon", "R")).isEqualTo("ghs_installation");
        assertThat(service.installationToken("hodoon", "r")).isEqualTo("ghs_installation");
    }

    @Test
    @DisplayName("설치 404 → INVALID_INPUT, 문구는 GitHubIssueSource.REPO_NOT_INSTALLED와 같다")
    void 설치_404는_INVALID_INPUT_REPO_NOT_INSTALLED문구() {
        server.expect(requestTo(API + "/repos/o/r/installation")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.installationToken("o", "r"))
            .isInstanceOf(CustomException.class)
            .hasMessage("이 레포에 GitHub 앱 설치가 필요합니다. GitHub App 설치 범위에 저장소를 추가해 주세요.")
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("JWT 401(키·client-id 불일치) → GITHUB_BOT_NOT_CONFIGURED")
    void JWT_401은_GITHUB_BOT_NOT_CONFIGURED() {
        server.expect(requestTo(API + "/repos/o/r/installation")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> service.installationToken("o", "r"))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("403 rate limit(x-ratelimit-remaining: 0) → GITHUB_API_UNAVAILABLE")
    void _403_rate_limit은_GITHUB_API_UNAVAILABLE() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-remaining", "0");
        server.expect(requestTo(API + "/repos/o/r/installation"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(headers));

        assertThatThrownBy(() -> service.installationToken("o", "r"))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.GITHUB_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("응답 token이 공백이면 GITHUB_API_UNAVAILABLE이고 캐시하지 않는다(다음 호출은 다시 발급)")
    void 빈_token은_GITHUB_API_UNAVAILABLE() {
        server.expect(requestTo(API + "/repos/o/r/installation"))
            .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/app/installations/42/access_tokens"))
            .andRespond(withSuccess("{\"token\":\"  \",\"expires_at\":\"" + EXPIRES_AT + "\"}", MediaType.APPLICATION_JSON));
        expectIssue("o", "r");

        assertErrorCode(() -> service.installationToken("o", "r"), ErrorCode.GITHUB_API_UNAVAILABLE);
        assertThat(service.installationToken("o", "r")).isEqualTo("ghs_installation");
    }

    @Test
    @DisplayName("응답 expires_at을 읽을 수 없으면 GITHUB_API_UNAVAILABLE")
    void 잘못된_expires_at은_GITHUB_API_UNAVAILABLE() {
        server.expect(requestTo(API + "/repos/o/r/installation"))
            .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/app/installations/42/access_tokens"))
            .andRespond(withSuccess("{\"token\":\"ghs_x\",\"expires_at\":\"tomorrow\"}", MediaType.APPLICATION_JSON));

        assertErrorCode(() -> service.installationToken("o", "r"), ErrorCode.GITHUB_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("installation id가 정수가 아니면 GITHUB_API_UNAVAILABLE")
    void 정수_아닌_installation_id는_GITHUB_API_UNAVAILABLE() {
        server.expect(requestTo(API + "/repos/o/r/installation"))
            .andRespond(withSuccess("{\"id\":\"42/../x\"}", MediaType.APPLICATION_JSON));

        assertErrorCode(() -> service.installationToken("o", "r"), ErrorCode.GITHUB_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("owner·repo가 경로 조각 하나가 아니면 HTTP 없이 INVALID_INPUT")
    void 경로_조각이_아니면_INVALID_INPUT() {
        assertErrorCode(() -> service.installationToken("a/../b", "r"), ErrorCode.INVALID_INPUT);
        assertErrorCode(() -> service.installationToken("o", ".."), ErrorCode.INVALID_INPUT);
        assertErrorCode(() -> service.installationToken("o", "."), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("rate limit 헤더 없는 403(앱 권한 부족 등) → GITHUB_BOT_NOT_CONFIGURED")
    void 헤더_없는_403은_GITHUB_BOT_NOT_CONFIGURED() {
        server.expect(requestTo(API + "/repos/o/r/installation")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertErrorCode(() -> service.installationToken("o", "r"), ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("429 → GITHUB_API_UNAVAILABLE")
    void _429는_GITHUB_API_UNAVAILABLE() {
        server.expect(requestTo(API + "/repos/o/r/installation")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertErrorCode(() -> service.installationToken("o", "r"), ErrorCode.GITHUB_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("500 → GITHUB_API_UNAVAILABLE")
    void _500은_GITHUB_API_UNAVAILABLE() {
        server.expect(requestTo(API + "/repos/o/r/installation")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertErrorCode(() -> service.installationToken("o", "r"), ErrorCode.GITHUB_API_UNAVAILABLE);
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(code);
    }

    private void expectIssue(String owner, String repo) {
        server.expect(once(), requestTo(API + "/repos/" + owner + "/" + repo + "/installation"))
            .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "/app/installations/42/access_tokens"))
            .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class MovableClock extends Clock {
        private Instant instant;

        MovableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
