package com.ieum.auth.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * GitHub App 설치 토큰 발급(spec 5.1) — App JWT(RS256)로 {@code GET /repos/{o}/{r}/installation} →
 * {@code POST /app/installations/{id}/access_tokens}. 토큰은 레포 하나·{@code pull_requests:write}로 좁힌다.
 *
 * <p>개인 키({@code github.app.private-key}, base64 PKCS#8)가 비면 기동은 되고 봇만 꺼진다.
 * 값이 있는데 파싱이 안 되면 생성자에서 실패한다(설정 오류를 숨기지 않음).
 * 토큰·JWT·키 원문은 로그·예외 메시지에 넣지 않는다.</p>
 */
@Slf4j
@Service
public class GitHubAppTokenService {

    static final String API_BASE = "https://api.github.com";
    // api 모듈 GitHubIssueSource.REPO_NOT_INSTALLED와 같은 글자(모듈 방향상 참조 불가 — 복사)
    static final String REPO_NOT_INSTALLED =
        "이 레포에 GitHub 앱 설치가 필요합니다. GitHub App 설치 범위에 저장소를 추가해 주세요.";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final String clientId;
    private final PrivateKey privateKey;
    private final RestTemplate restTemplate;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public GitHubAppTokenService(@Value("${github.app.client-id}") String clientId,
                                 @Value("${github.app.private-key:}") String privateKeyBase64,
                                 RestTemplate restTemplate) {
        this(clientId, privateKeyBase64, restTemplate, Clock.systemUTC());
    }

    GitHubAppTokenService(String clientId, String privateKeyBase64, RestTemplate restTemplate, Clock clock) {
        this.clientId = clientId;
        this.privateKey = parsePrivateKey(privateKeyBase64);
        this.restTemplate = restTemplate;
        this.clock = clock;
    }

    /** 개인 키가 설정됐는가. */
    public boolean enabled() {
        return privateKey != null;
    }

    /**
     * {@code owner/repo} 하나·{@code pull_requests:write}로 좁힌 설치 토큰. 만료 5분 전까지 메모리 캐시를 재사용한다.
     *
     * @throws CustomException GITHUB_BOT_NOT_CONFIGURED — 키 없음·JWT 거부 / INVALID_INPUT — 레포에 앱 미설치 /
     *                         GITHUB_API_UNAVAILABLE — rate limit·장애
     */
    public String installationToken(String owner, String repo) {
        // 템플릿 변수는 '/'를 인코딩하지 않는다 — 경로 조각 하나로만 받는다(경로 조작 방지)
        if (!isPathSegment(owner) || !isPathSegment(repo)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "GitHub 레포 이름 형식이 올바르지 않습니다.");
        }
        if (!enabled()) {
            throw new CustomException(ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
        }
        String key = (owner + "/" + repo).toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && clock.instant().isBefore(cached.expiresAt().minus(REFRESH_MARGIN))) {
            return cached.token();
        }
        Cached issued = issue(owner, repo);
        cache.put(key, issued);
        return issued.token();
    }

    @SuppressWarnings("unchecked")
    private Cached issue(String owner, String repo) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appJwt());
        headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        try {
            Map<String, Object> installation = restTemplate.exchange(
                API_BASE + "/repos/{owner}/{repo}/installation", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class, owner, repo).getBody();
            Object id = installation == null ? null : installation.get("id");
            if (!(id instanceof Integer || id instanceof Long)) {
                throw new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
            }
            Map<String, Object> body = Map.of(
                "repositories", List.of(repo),
                "permissions", Map.of("pull_requests", "write"));
            Map<String, Object> response = restTemplate.exchange(
                API_BASE + "/app/installations/{id}/access_tokens", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class, id).getBody();
            Object token = response == null ? null : response.get("token");
            Object expiresAt = response == null ? null : response.get("expires_at");
            if (!(token instanceof String t) || t.isBlank() || expiresAt == null) {
                throw new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
            }
            try {
                return new Cached(t, Instant.parse(expiresAt.toString()));
            } catch (DateTimeParseException e) {
                throw new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            log.warn("[GitHubAppTokenService] 설치 토큰 발급 거부 — {}/{} status: {}", owner, repo, status);
            throw clientError(status, e.getResponseHeaders());
        } catch (RestClientException e) {
            log.warn("[GitHubAppTokenService] 설치 토큰 발급 실패 — {}/{} {}", owner, repo, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
        }
    }

    private static CustomException clientError(int status, HttpHeaders headers) {
        // GitHubApiReader.clientError와 같은 판정 — 1차(x-ratelimit-remaining: 0)·2차(retry-after) rate limit은 403
        if (status == 429 || (status == 403 && headers != null
            && ("0".equals(headers.getFirst("x-ratelimit-remaining")) || headers.containsKey(HttpHeaders.RETRY_AFTER)))) {
            return new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
        }
        if (status == 404) {
            return new CustomException(ErrorCode.INVALID_INPUT, REPO_NOT_INSTALLED);
        }
        // 401(JWT 거부: 키·client-id 불일치)·그 밖 4xx(App 권한 부족 등) — 사용자가 못 고치는 서버 설정 문제
        return new CustomException(ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
    }

    private static boolean isPathSegment(String s) {
        return s != null && PATH_SEGMENT.matcher(s).matches() && !s.equals(".") && !s.equals("..");
    }

    private String appJwt() {
        Instant now = clock.instant();
        return Jwts.builder()
            .issuer(clientId)
            .issuedAt(Date.from(now.minusSeconds(60)))
            .expiration(Date.from(now.plus(Duration.ofMinutes(9))))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    private static PrivateKey parsePrivateKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64.strip());
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            // 원인 예외 메시지에 키 조각이 섞일 수 있어 붙이지 않는다
            throw new IllegalStateException("github.app.private-key(GITHUB_APP_PRIVATE_KEY)를 base64 PKCS#8 RSA 키로 읽을 수 없습니다");
        }
    }

    private record Cached(String token, Instant expiresAt) {
    }
}
