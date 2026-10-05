package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.service.GoogleTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google 공급원들이 공유하는 GET 호출 — 권한 사전 검사, 토큰, 오류 매핑을 한 곳에 둔다.
 *
 * <p>트랜잭션을 걸지 않는다. {@link GoogleTokenService#getValidAccessToken}이 자체 트랜잭션으로 갱신
 * 토큰을 저장하는데, 바깥 readOnly 트랜잭션에 합류하면 그 저장이 flush되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GoogleApiReader {

    static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    static final String SPREADSHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar";
    private static final String DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files";

    private final GoogleTokenService googleTokenService;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final RestTemplate restTemplate;

    /**
     * @param acceptedScopes 이 중 하나라도 승인돼 있으면 통과
     * @return 응답 본문. 비어 있으면 빈 객체 노드
     */
    JsonNode get(UUID userId, URI uri, String... acceptedScopes) {
        requireScope(userId, acceptedScopes);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(googleTokenService.getValidAccessToken(userId));
        try {
            JsonNode body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class)
                .getBody();
            return body != null ? body : JsonNodeFactory.instance.objectNode();
        } catch (HttpClientErrorException e) {
            // 본문 message엔 리소스 ID가 들어 있을 수 있어 reason 필드만 남긴다
            log.warn("[GoogleApiReader] Google 요청 거부 — host: {}, status: {}, reason: {}",
                uri.getHost(), e.getStatusCode().value(), errorReason(e));
            throw clientError(e.getStatusCode().value());
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패 — 사용자가 고칠 수 없는 일시 장애
            log.warn("[GoogleApiReader] Google 호출 실패 — host: {}, {}", uri.getHost(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.GOOGLE_API_UNAVAILABLE);
        }
    }

    /**
     * Drive {@code files.list} 한 페이지 — 최근 수정순 50개. scope는 {@code drive}.
     * {@code corpora}가 기본값(user)이라 사용자가 만들었거나 열었거나 직접 공유받은 항목만 나온다 —
     * 공유 드라이브 항목도 이 조건을 만족할 때만 포함된다.
     *
     * @param q Drive 검색식. URI 변수로 넣어 엄격 인코딩한다
     */
    OptionPage listDriveFiles(UUID userId, String q, String cursor) {
        // 값은 URI 변수로 넣어 엄격 인코딩한다 — cursor에 &·=가 섞여도 파라미터로 새지 않는다.
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(DRIVE_FILES_URL)
            .queryParam("q", "{q}")
            .queryParam("orderBy", "modifiedTime desc")
            .queryParam("pageSize", 50)
            .queryParam("fields", "nextPageToken,files(id,name)")
            .queryParam("supportsAllDrives", true)
            .queryParam("includeItemsFromAllDrives", true);
        Map<String, Object> vars = new HashMap<>(Map.of("q", q));
        if (cursor != null) {
            uri.queryParam("pageToken", "{cursor}");
            vars.put("cursor", cursor);
        }

        JsonNode body = get(userId, uri.encode().buildAndExpand(vars).toUri(), DRIVE_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        body.path("files").forEach(file ->
            items.add(new OptionItem(file.path("id").asText(), file.path("name").asText())));
        return new OptionPage(items, body.path("nextPageToken").asText(null));
    }

    /** scope는 토큰 단위 정확 일치 — 부분 문자열로 보면 {@code drive.file}이 {@code drive}를 통과한다. */
    private void requireScope(UUID userId, String... acceptedScopes) {
        ConnectedAccount account = connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.GOOGLE)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));
        String scopes = account.getScopes();
        List<String> granted = scopes == null ? List.of() : Arrays.asList(scopes.trim().split("[,\\s]+"));
        if (Arrays.stream(acceptedScopes).noneMatch(granted::contains)) {
            throw new CustomException(ErrorCode.GOOGLE_SCOPE_REQUIRED);
        }
    }

    /** Drive v3·Sheets v4 {@code error.errors[0].reason} → 없으면 {@code error.status} → {@code "unknown"}. */
    private static String errorReason(HttpClientErrorException e) {
        try {
            JsonNode error = e.getResponseBodyAs(JsonNode.class).path("error");
            String reason = error.path("errors").path(0).path("reason").asText(null);
            return reason != null ? reason : error.path("status").asText("unknown");
        } catch (RuntimeException ignored) {
            return "unknown"; // 본문 없음·JSON 아님(변환 불가 시 null도 여기로)
        }
    }

    private static CustomException clientError(int status) {
        return switch (status) {
            case 401 -> new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
            case 403, 404 -> new CustomException(ErrorCode.GOOGLE_RESOURCE_NOT_FOUND);
            case 429 -> new CustomException(ErrorCode.GOOGLE_API_UNAVAILABLE);
            // 조작·만료된 pageToken 등 — 요청 값 문제다
            default -> new CustomException(ErrorCode.INVALID_INPUT, "Google이 요청을 거부했습니다 (HTTP " + status + ").");
        };
    }
}
