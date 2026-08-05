package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.util.Map;

/**
 * 노드 {@code config.url}에 Slack·Discord 웹훅 URL 원문이 담기는 것을 막는 공통 판정 (IEUM-BE-62).
 *
 * <p>웹훅 URL은 그 자체가 비밀인데 노드 config는 Mongo에 평문으로 저장되고 조회 응답에도 그대로
 * 실린다. 대안은 이미 있다 — 웹훅 자격증명을 등록하고 {@code config.webhookCredentialId}로 참조하면
 * {@code HttpNodeExecutor}가 실행 시점에만 복호해 쓴다(Task 1).
 *
 * <p>저장 경로가 둘이라 여기 모아 뒀다. REST 생성·수정({@link WorkflowService})은 {@code NodeDto}를,
 * 채팅으로 agent가 만든 정의({@code ChatService})는 역직렬화한 {@code Map}을 들고 오는데, 둘이 각자
 * 메시지·ErrorCode를 갖고 있으면 같은 위반에 다른 반응이 나온다.
 *
 * <p>도메인 판정은 {@link SensitiveDataMasker#containsWebhookUrl}에 맡긴다 — 마스킹과 저장 거부가
 * 같은 정규식을 봐야 도메인이 추가될 때 한쪽만 고쳐지는 일이 없다.
 */
public final class RawWebhookUrlGuard {

    private RawWebhookUrlGuard() {
    }

    /**
     * config에 리터럴 웹훅 URL이 있으면 400으로 거부한다.
     *
     * <p>검사 대상은 {@code url} 키의 문자열 값뿐이다. 변수 참조({@code {{nodes.x.output.url}}})처럼
     * 실행 시점에야 웹훅 URL이 되는 값이나 {@code body} 같은 다른 필드에 숨긴 URL은 걸리지 않는다.
     *
     * @param config 노드 config. {@code null}이거나 {@code Map}이 아니면 검사할 것이 없다
     */
    public static void rejectRawWebhookUrl(Object config) {
        if (config instanceof Map<?, ?> map
                && map.get("url") instanceof String url
                && SensitiveDataMasker.containsWebhookUrl(url)) {
            // 메시지에도 로그에도 URL·노드 식별자를 싣지 않는다. GlobalExceptionHandler가 예외 메시지를
            // 그대로 WARN 로그에 남기므로 여기 담는 값이 곧 로그에 남는다.
            throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                "노드 config.url에 Slack·Discord 웹훅 URL을 직접 저장할 수 없습니다. "
                    + "웹훅 자격증명을 등록한 뒤 config.webhookCredentialId로 참조하세요.");
        }
    }
}
