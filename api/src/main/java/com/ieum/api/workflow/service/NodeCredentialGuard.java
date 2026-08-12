package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 노드 {@code config.credentialId}에 남의 크레덴셜이 담기는 것을 막는 공통 판정 (IEUM-BE-64).
 *
 * <p>복호화 자체는 {@code CredentialService.decrypt}가 소유자 검증으로 이미 막는다(Task 1). 여기는
 * 두 번째 겹이다 — 저장은 되고 실행에서야 {@code NOT_FOUND}로 죽으면 사용자에게 원인이 보이지 않으므로
 * 저장에서 400으로 알린다.
 *
 * <p>저장 경로가 둘이라 {@link RawWebhookUrlGuard}와 같은 자리에 뒀다: REST 생성·수정
 * ({@link WorkflowService})은 {@code NodeDto}를, 채팅으로 agent가 만든 정의({@code ChatService})는
 * 역직렬화한 {@code Map}을 들고 온다.
 */
public final class NodeCredentialGuard {

    private NodeCredentialGuard() {
    }

    /**
     * config의 credentialId가 소유 목록에 없으면 400으로 거부한다.
     *
     * <p>검사 대상은 {@code credentialId} 키의 문자열 값뿐이다. 빈 값은 통과시킨다 — agent가 자리만
     * 만들고 비워 보내면 {@code ChatService}가 서버가 고른 크레덴셜을 채운다.
     *
     * <p>비교 전에 값을 다듬는다(trim + 소문자). UUID 표기는 대소문자를 가리지 않아
     * {@code UUID.fromString}은 대문자 표기도 같은 크레덴셜로 읽는데, 여기서 문자열을 그대로 맞대면
     * 본인 소유인데도 거부당한다.
     *
     * @param config 노드 config. {@code null}이거나 {@code Map}이 아니면 검사할 것이 없다
     * @param ownedCredentialIds 요청자가 가진 크레덴셜 ID 문자열 집합({@code UUID.toString()} — 소문자)
     */
    public static void rejectForeignCredentialId(Object config, Set<String> ownedCredentialIds) {
        if (!(config instanceof Map<?, ?> map)) {
            return;
        }
        rejectIfForeign(map.get("credentialId"), ownedCredentialIds);

        // 도구 인증도 같은 credentialId를 읽어 실제로 복호화한다(ToolAuthResolver.resolveAuthValue).
        // 모양이 둘이다 — auth 맵이 없을 때의 평면 tools[].credentialId, 그리고 구조화
        // tools[].auth.credentialId(type이 없어도 credentialId가 있으면 "credential"로 간주된다).
        // 최상위만 보면 같은 IDOR가 도구 레벨에 그대로 남는다.
        //
        // 중첩은 한 겹(tools[].auth)까지다. 임의 깊이 재귀로 넓히지 말 것 — auth.type이
        // "secret"·"plain"이면 auth.value에 비밀 원문이 정당하게 들어가는 기존 기능이 있다.
        if (!(map.get("tools") instanceof List<?> tools)) {
            return;
        }
        for (Object tool : tools) {
            // 저장 스키마가 강제되지 않으므로 모양이 어긋나면 형변환 예외 대신 건너뛴다.
            if (!(tool instanceof Map<?, ?> toolMap)) {
                continue;
            }
            rejectIfForeign(toolMap.get("credentialId"), ownedCredentialIds);
            if (toolMap.get("auth") instanceof Map<?, ?> auth) {
                rejectIfForeign(auth.get("credentialId"), ownedCredentialIds);
            }
        }
    }

    /**
     * credentialId 한 자리를 판정한다 — 최상위와 도구가 같은 정규화·같은 메시지를 쓰게 하는 자리다.
     * 판정이 두 벌로 갈라지면 한쪽만 고쳐진다.
     */
    private static void rejectIfForeign(Object value, Set<String> ownedCredentialIds) {
        if (value instanceof String credentialId
                && !credentialId.isBlank()
                && !ownedCredentialIds.contains(credentialId.trim().toLowerCase(Locale.ROOT))) {
            // 메시지에 credentialId를 싣지 않는다 — GlobalExceptionHandler가 메시지를 그대로 로그에 남기고,
            // 존재 여부를 되돌려 주면 남의 크레덴셜 ID를 탐색하는 신호가 된다.
            throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                "노드 config.credentialId가 본인 소유 크레덴셜이 아닙니다.");
        }
    }

    /**
     * 비밀 원문을 담는 최상위 config 키를 거부한다 (IEUM-BE-64).
     *
     * <p>노드 config는 Mongo에 평문으로 저장되고 조회 응답에 그대로 실린다. 비밀은 크레덴셜로 등록하고
     * {@code credentialId}로 참조해야 한다 — 실행 시점에만 복호된다.
     *
     * <p>전체 키 화이트리스트는 만들지 않는다. 노드 타입마다 config 키가 다르고 FE가 새 키를 더할 때마다
     * 400이 나면 하드 블로커가 된다(IEUM-BE-60 전례). 비밀 성격의 키만 거부한다.
     *
     * <p>검사 범위는 <b>최상위 키의 문자열 값뿐</b>이다. 중첩 Map은 보지 않는다 — HTTP 노드
     * {@code headers}의 {@code Authorization}이나 {@code tools[].auth.value}(type이
     * {@code secret}·{@code plain}일 때)는 정당한 원문 자리라, 거기까지 막으면 기존 기능이 죽는다.
     * 변수 참조({@code {{...}}})는 저장 시점에 비밀이 아니므로 통과시킨다.
     */
    public static void rejectInlineSecret(Object config) {
        if (!(config instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key
                    && SECRET_KEYS.contains(key.trim().toLowerCase(Locale.ROOT))
                    && entry.getValue() instanceof String value
                    && !value.isBlank()
                    && !isVariableReference(value)) {
                // 메시지에 위반한 값을 되싣지 않는다 — 막으려던 원문이 그대로 WARN 로그로 흘러간다.
                throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                    "노드 config에 API 키·토큰 원문을 저장할 수 없습니다. "
                        + "크레덴셜을 등록한 뒤 config.credentialId로 참조하세요.");
            }
        }
    }

    /**
     * 키 비교는 소문자로 한다 — {@code apiKey}·{@code apikey}·{@code APIKEY}가 모두 걸린다.
     *
     * <p>{@code credentialId}·{@code webhookCredentialId}·{@code catalogId}는 참조용 ID라 넣지 않는다.
     * {@code token} 단독도 넣지 않는다 — 정당한 용례가 없다는 확증이 없고, 실제로 걸리는 값은 대부분
     * {@code accessToken} 쪽이다.
     */
    private static final Set<String> SECRET_KEYS = Set.of(
        "apikey", "api_key", "accesstoken", "access_token", "secret", "password", "privatekey");

    private static boolean isVariableReference(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{{") && trimmed.endsWith("}}");
    }
}
