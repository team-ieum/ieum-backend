package com.ieum.workflowcore.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 민감 필드 마스킹 공용 유틸.
 *
 * <p>마스킹 규칙이 여러 곳으로 갈라지지 않도록 민감값 가림은 전부 이 클래스를 거친다.
 * 현재 호출부는 {@code SyncExecutionRuntime.saveExecutionLog()}의 {@code node_runs} 입출력 저장이다
 * ({@code workflow_runs.trigger_data}는 마스킹이 아니라 AES-256 암호화 경로다).
 *
 * <p>중첩 {@code Map}·{@code List} 안쪽까지 재귀로 적용하며, 값이 문자열이면 Slack/Discord
 * 웹훅 URL의 토큰 구간도 가린다. 입력은 변형하지 않고 새 컬렉션을 반환한다.
 *
 * <p>웹훅 URL 판정({@link #containsWebhookUrl}, {@link #isSlackWebhookUrl},
 * {@link #isDiscordWebhookUrl})도 여기서 함께 제공한다 — 마스킹·저장 거부·등록 검증이 같은
 * 도메인 집합을 보게 하려면 도메인 조각이 한 곳에만 있어야 하기 때문이다. 셋 다
 * {@code SLACK_WEBHOOK_PREFIX}·{@code DISCORD_WEBHOOK_PREFIX}를 조립해 쓴다.
 */
public final class SensitiveDataMasker {

    private static final String MASK = "***";

    private static final Set<String> SENSITIVE_KEYS =
        Set.of("apiKey", "api_key", "token", "secret", "password", "Authorization");

    /**
     * Slack 웹훅 URL의 호스트 + 경로 접두. 발신용 Incoming Webhook({@code /services})과
     * 워크플로우 트리거 웹훅({@code /triggers}, {@code /workflows})을 모두 포함한다 — 셋 다
     * URL 자체가 비밀이고 전부 {@code hooks.slack.com}에만 존재한다.
     */
    private static final String SLACK_WEBHOOK_PREFIX =
        "hooks\\.slack\\.com/(?:services|triggers|workflows)";

    /**
     * Discord 웹훅 URL의 호스트 + 경로 접두. {@code discordapp.com}(구 도메인)과
     * {@code ptb.}·{@code canary.} 같은 서브도메인, {@code /api/v10/webhooks}처럼 버전이 낀
     * 경로까지 받는다 — Discord가 실제로 안내하는 형태들이다.
     */
    private static final String DISCORD_WEBHOOK_PREFIX =
        "(?:[\\w-]+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks";

    /**
     * 웹훅 URL의 비밀 구간. 호스트와 {@code /services/}·{@code /api/webhooks/}까지는 남겨
     * 어느 서비스인지 알아볼 수 있게 하고, 그 뒤 경로만 가린다.
     */
    private static final Pattern WEBHOOK_URL_PATTERN = Pattern.compile(
        "(?i)(https?://(?:" + SLACK_WEBHOOK_PREFIX + "|" + DISCORD_WEBHOOK_PREFIX + ")/)[^\\s\"']+");

    /** 등록 검증용 — {@link #isSlackWebhookUrl} 참조. */
    private static final Pattern SLACK_WEBHOOK_URL = registrationPattern(SLACK_WEBHOOK_PREFIX);

    /** 등록 검증용 — {@link #isDiscordWebhookUrl} 참조. */
    private static final Pattern DISCORD_WEBHOOK_URL = registrationPattern(DISCORD_WEBHOOK_PREFIX);

    /**
     * 재귀 깊이 상한. 방문 추적(IdentityHashMap) 대신 깊이 제한을 쓴 이유는 두 가지다.
     * (1) 이 유틸의 입력은 Mongo 문서·노드 입출력이라 자기 참조가 아니라 단순히 깊은 구조로도
     * 스택을 밀 수 있는데, 깊이 제한은 순환과 과도한 깊이를 한 번에 막는다.
     * (2) 방문 집합은 호출마다 추가 자료구조가 필요하고, 같은 하위 Map을 여러 키가 공유하는
     * 정상 입력에서 뒤쪽 참조를 순환으로 오인해 값을 잃는다.
     * 상한을 넘으면 값을 그대로 두지 않고 {@code ***}로 가린다 — 그 아래를 검사하지 못한 이상
     * 민감값이 섞여 있을 수 있어 안전한 쪽으로 닫는다.
     */
    private static final int MAX_DEPTH = 20;

    private SensitiveDataMasker() {
    }

    /**
     * 민감값을 가린 새 Map을 반환한다. 입력 Map은 변경하지 않는다.
     * 입력이 null이면 null을 반환하며, 그 외 어떤 입력에도 예외를 던지지 않는다.
     */
    public static Map<String, Object> mask(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        return maskMap(data, 0);
    }

    private static Map<String, Object> maskMap(Map<?, ?> data, int depth) {
        Map<String, Object> masked = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            String key = (k == null) ? null : k.toString();
            if (key != null && isSensitiveKey(key)) {
                masked.put(key, MASK);
            } else {
                masked.put(key, maskValue(v, depth + 1));
            }
        });
        return masked;
    }

    private static Object maskValue(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            return depth > MAX_DEPTH ? MASK : maskMap(map, depth);
        }
        if (value instanceof List<?> list) {
            if (depth > MAX_DEPTH) {
                return MASK;
            }
            List<Object> masked = new ArrayList<>(list.size());
            for (Object item : list) {
                masked.add(maskValue(item, depth + 1));
            }
            return masked;
        }
        if (value instanceof String s) {
            return maskWebhookUrl(s);
        }
        return value;
    }

    /**
     * 문자열 안에 Slack·Discord 웹훅 URL이 들어 있으면 true.
     *
     * <p>마스킹과 같은 정규식을 쓴다 — 노드 config에 원문 저장을 거부하는 쪽(api
     * {@code RawWebhookUrlGuard})이 별도 정규식을 두면 도메인 하나가 추가될 때 한쪽만 고쳐진다.
     * 도메인이 늘면 {@code SLACK_WEBHOOK_PREFIX}·{@code DISCORD_WEBHOOK_PREFIX}만 고친다 —
     * 마스킹·저장 거부·등록 검증이 전부 그 두 조각으로 조립된다.
     *
     * <p>토큰 구간(호스트 뒤 경로)이 있는 URL만 잡는다. 호스트만 적힌 문자열은 웹훅으로 쓸 수 없어
     * 가릴 비밀도, 거부할 이유도 없다.
     */
    public static boolean containsWebhookUrl(String value) {
        return value != null && WEBHOOK_URL_PATTERN.matcher(value).find();
    }

    /**
     * 문자열 전체가 Slack 웹훅 URL 하나인지. 웹훅 자격증명 등록 검증용이다
     * (api {@code WebhookCredentialService.create}).
     *
     * <p>{@link #containsWebhookUrl}과 달리 <b>전체 일치</b>이고 {@code https}만 받는다.
     * 등록된 URL은 실행 시점에 그대로 호출 대상이 되므로, 부분 일치를 허용하면
     * {@code https://evil.example/?u=https://hooks.slack.com/services/x} 같은 값이 통과해
     * 실제 요청은 다른 호스트로 나간다. 평문 {@code http}는 Slack·Discord 어느 쪽도 쓰지 않고
     * 웹훅 URL 자체가 비밀이라 받지 않는다.
     */
    public static boolean isSlackWebhookUrl(String value) {
        return value != null && SLACK_WEBHOOK_URL.matcher(value).matches();
    }

    /** 문자열 전체가 Discord 웹훅 URL 하나인지. 판정 기준은 {@link #isSlackWebhookUrl}과 같다. */
    public static boolean isDiscordWebhookUrl(String value) {
        return value != null && DISCORD_WEBHOOK_URL.matcher(value).matches();
    }

    /** 호스트 뒤에 경로가 최소 한 구간 있어야 통과한다 — 토큰 없는 URL은 웹훅으로 쓸 수 없다. */
    private static Pattern registrationPattern(String prefix) {
        return Pattern.compile("(?i)https://" + prefix + "/[^\\s\"']+");
    }

    private static boolean isSensitiveKey(String key) {
        String lowered = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(s -> lowered.contains(s.toLowerCase()));
    }

    /** 웹훅 URL이 아니면 원본 문자열을 그대로 돌려준다. 문자열 중간에 섞인 URL도 가린다. */
    private static String maskWebhookUrl(String value) {
        return WEBHOOK_URL_PATTERN.matcher(value).replaceAll("$1" + MASK);
    }
}
