package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.service.SlackApiClient;
import com.ieum.auth.service.SlackTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.SlackTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * 연결한 Slack 워크스페이스의 공개 채널(보관 제외) — 값은 채널 ID, 이름은 {@code #이름}(spec §10). 비공개 채널은 이번 노드에 없다.
 * 검색어 {@code q}가 없으면 Slack 커서를 그대로 넘기고, 있으면 200개씩 최대 5페이지에서 이름 부분 일치 20개를 모아 한 번에 준다.
 * 트랜잭션을 걸지 않는다(options 관례 — 토큰 갱신 저장이 readOnly에 합류해 유실되지 않게).
 * conversations.list는 Tier 2(분당 20+)이고 워크스페이스 단위라 사용자 전원이 나눠 쓴다.
 * ponytail: 캐시 없음 — 429가 SLACK_API_UNAVAILABLE로 드러나면 팀 단위 짧은 캐시를 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackChannelOptionSource implements OptionSource {

    static final int PAGE_SIZE = 20;
    static final int SEARCH_PAGE_SIZE = 200;
    static final int SEARCH_MAX_PAGES = 5;

    private static final Set<String> RECONNECT_ERRORS =
        Set.of("invalid_auth", "account_inactive", "token_revoked", "token_expired", "not_authed");
    private static final Set<String> TRANSIENT_ERRORS = Set.of(
        "ratelimited", "internal_error", "service_unavailable", "fatal_error", "request_timeout", "empty_response");

    private final SlackTokenProvider slackTokenProvider;
    private final SlackApiClient slackApiClient;

    @Override
    public String key() {
        return "slack.channels";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String token = slackTokenProvider.getValidBotToken(userId);
        String q = normalize(inputs.get("q"));
        if (q == null) {
            JsonNode body = list(token, PAGE_SIZE, cursor);
            return new OptionPage(items(body, null, PAGE_SIZE), nextCursor(body));
        }
        List<OptionItem> found = new ArrayList<>();
        String pageCursor = null; // 검색은 들어온 cursor를 쓰지 않는다 — nextCursor를 주지 않으므로
        for (int page = 0; page < SEARCH_MAX_PAGES && found.size() < PAGE_SIZE; page++) {
            JsonNode body = list(token, SEARCH_PAGE_SIZE, pageCursor);
            found.addAll(items(body, q, PAGE_SIZE - found.size()));
            pageCursor = nextCursor(body);
            if (pageCursor == null) {
                break;
            }
        }
        return new OptionPage(found, null);
    }

    /** 공백뿐이면 없음. 라벨이 {@code #이름}이라 앞의 #은 떼고, 소문자로 비교한다 */
    private static String normalize(String q) {
        if (q == null) {
            return null;
        }
        String stripped = q.strip();
        if (stripped.startsWith("#")) {
            stripped = stripped.substring(1).strip();
        }
        return stripped.isEmpty() ? null : stripped.toLowerCase(Locale.ROOT);
    }

    private static List<OptionItem> items(JsonNode body, String q, int limit) {
        List<OptionItem> items = new ArrayList<>();
        for (JsonNode channel : body.path("channels")) {
            if (items.size() == limit) {
                break;
            }
            String name = channel.path("name").asText("");
            if (q == null || name.toLowerCase(Locale.ROOT).contains(q)) {
                items.add(new OptionItem(channel.path("id").asText(), "#" + name));
            }
        }
        return items;
    }

    /** Slack은 마지막 페이지에 빈 문자열을 준다 */
    private static String nextCursor(JsonNode body) {
        String next = body.path("response_metadata").path("next_cursor").asText("");
        return next.isEmpty() ? null : next;
    }

    /** Slack은 오류도 HTTP 200 + ok:false로 준다 — HTTP 상태만 보면 조용히 빈 목록이 된다 */
    private JsonNode list(String token, int limit, String cursor) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("types", "public_channel");
        form.add("exclude_archived", "true");
        form.add("limit", String.valueOf(limit));
        if (cursor != null) {
            form.add("cursor", cursor);
        }
        JsonNode body;
        try {
            body = slackApiClient.call("conversations.list", form, token);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                throw new CustomException(ErrorCode.SLACK_API_UNAVAILABLE);
            }
            throw new CustomException(ErrorCode.INVALID_INPUT, "Slack이 요청을 거부했어요 (HTTP " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패 — 사용자가 고칠 수 없는 일시 장애
            log.warn("[SlackChannelOptionSource] conversations.list 호출 실패 — {}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.SLACK_API_UNAVAILABLE);
        }
        if (body.path("ok").asBoolean(false)) {
            return body;
        }
        String error = body.path("error").asText("unknown");
        log.warn("[SlackChannelOptionSource] conversations.list 거절 — error: {}", error);
        if (RECONNECT_ERRORS.contains(error)) {
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, SlackTokenService.RECONNECT_MESSAGE);
        }
        if ("missing_scope".equals(error)) {
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, "Slack 앱 권한이 부족해요. 다시 연결해 주세요");
        }
        if (TRANSIENT_ERRORS.contains(error)) {
            throw new CustomException(ErrorCode.SLACK_API_UNAVAILABLE);
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, "Slack이 요청을 거부했어요 (" + error + ")");
    }
}
