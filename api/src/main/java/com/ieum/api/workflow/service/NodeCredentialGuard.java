package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
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
        if (config instanceof Map<?, ?> map
                && map.get("credentialId") instanceof String credentialId
                && !credentialId.isBlank()
                && !ownedCredentialIds.contains(credentialId.trim().toLowerCase(Locale.ROOT))) {
            // 메시지에 credentialId를 싣지 않는다 — GlobalExceptionHandler가 메시지를 그대로 로그에 남기고,
            // 존재 여부를 되돌려 주면 남의 크레덴셜 ID를 탐색하는 신호가 된다.
            throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                "노드 config.credentialId가 본인 소유 크레덴셜이 아닙니다.");
        }
    }
}
