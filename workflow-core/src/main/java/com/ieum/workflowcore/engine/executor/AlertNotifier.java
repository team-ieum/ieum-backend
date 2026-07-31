package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * 실행 실패 알림을 발신하는 포트 인터페이스.
 * api 모듈의 어댑터(DefaultAlertNotifier)가 Discord Incoming Webhook으로 구현하여 주입된다.
 *
 * <p>workflow-core는 api 모듈(웹훅 자격증명·Redis·RestTemplate)에 직접 의존하지 않으므로
 * 의존성 역전을 위해 이 포트로 발신한다.
 *
 * <p><b>구현체는 절대 예외를 던지지 않아야 한다</b> — 알림 발신 실패가 실행 종료 처리를 깨면 안 된다.
 * 호출부도 try-catch로 한 번 더 감싸지만, 책임의 1차 소재는 구현체다.
 */
public interface AlertNotifier {

    /**
     * 실행이 FAILED로 확정되었음을 알린다.
     *
     * @param alert 알림에 실을 정보. 자격증명·API 키·프롬프트 원문은 담지 않는다.
     */
    void notifyExecutionFailed(ExecutionFailureAlert alert);

    /**
     * 실패 알림 페이로드.
     *
     * <p>메시지에 실리는 값은 여기 있는 것뿐이다 — 민감정보가 채널로 새지 않게 하려면
     * 필드를 늘리지 말고 여기서 막는다.
     *
     * @param failedNodeId 실패한 노드 ID. 노드 실행 이전(정의 로드 실패 등)이나 런타임 진입 전
     *                     실패에서는 null이다.
     * @param errorSummary 오류 요약. 프롬프트 원문·자격증명이 섞이지 않는 값만 넘긴다
     *                     (예외 메시지, 노드 실행 결과의 errorMessage).
     * @param retryExhausted 재시도 대상 실패로 재시도를 모두 소진하고도 실패했는지 (문구 구분용)
     */
    record ExecutionFailureAlert(
        UUID executionId,
        UUID workflowId,
        String workflowName,
        UUID ownerUserId,
        String failedNodeId,
        String errorSummary,
        boolean retryExhausted
    ) {}
}
