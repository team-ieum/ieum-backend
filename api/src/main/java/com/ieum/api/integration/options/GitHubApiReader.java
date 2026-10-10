package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * GitHub 공급원들이 공유하는 GET 호출 — 토큰, 헤더, 오류 매핑을 한 곳에 둔다.
 *
 * <p>트랜잭션을 걸지 않는다(options 패키지 관례). 토큰은 GitHub App user-to-server 토큰이라
 * {@code GitHubTokenProvider}가 필요하면 갱신한다. 이 토큰으로 보이는 목록은 <b>App 설치 범위</b>로
 * 한정된다(설치 밖 org·repo는 오지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GitHubApiReader {

    static final String API_BASE = "https://api.github.com";
    static final int PAGE_SIZE = 50;

    private final GitHubTokenProvider gitHubTokenProvider;
    private final RestTemplate restTemplate;

    /** @param hasNext 응답 {@code Link} 헤더에 {@code rel="next"}가 있는지 */
    record Page(JsonNode body, boolean hasNext) {
    }

    /**
     * @param pathSegments 경로 조각 — 각각 인코딩된다(호출부가 형식도 검증한다)
     * @param query        쿼리 파라미터(삽입 순서 유지)
     */
    Page get(UUID userId, List<String> pathSegments, Map<String, String> query) {
        String token = gitHubTokenProvider.getAccessToken(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(API_BASE)
            .pathSegment(pathSegments.toArray(String[]::new));
        query.forEach(builder::queryParam);
        URI uri = builder.build().encode().toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.set(HttpHeaders.USER_AGENT, "ieum-backend");
        try {
            ResponseEntity<JsonNode> response =
                restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            JsonNode body = response.getBody() != null ? response.getBody() : JsonNodeFactory.instance.arrayNode();
            String link = response.getHeaders().getFirst(HttpHeaders.LINK);
            return new Page(body, link != null && link.contains("rel=\"next\""));
        } catch (HttpClientErrorException e) {
            // 응답 본문엔 사용자 정보가 섞일 수 있어 상태 코드만 남긴다
            log.warn("[GitHubApiReader] GitHub 요청 거부 — status: {}", e.getStatusCode().value());
            throw clientError(e.getStatusCode().value());
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패 — 사용자가 고칠 수 없는 일시 장애
            log.warn("[GitHubApiReader] GitHub 호출 실패 — {}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
        }
    }

    private static CustomException clientError(int status) {
        return switch (status) {
            // 기본 문구가 Google 재연동이라 덮어쓴다
            case 401 -> new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, "GitHub 계정을 다시 연동해주세요.");
            // 권한 부족(App 설치 범위·권한) 또는 요청 한도 초과 — 사용자가 고칠 길은 설치·권한 확인이다
            case 403 -> new CustomException(ErrorCode.INVALID_INPUT,
                "GitHub 연동 권한이 부족하거나 요청 한도를 넘었습니다. GitHub App 설치 범위와 권한을 확인해 주세요.");
            case 429 -> new CustomException(ErrorCode.GITHUB_API_UNAVAILABLE);
            // 없는 org(404)·잘못된 요청 등 — 요청 값 문제다
            default -> new CustomException(ErrorCode.INVALID_INPUT, "GitHub가 요청을 거부했습니다 (HTTP " + status + ").");
        };
    }
}
