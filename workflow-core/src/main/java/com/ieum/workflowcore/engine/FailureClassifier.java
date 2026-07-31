package com.ieum.workflowcore.engine;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 실패 원인을 {@link FailureKind}로 분류한다. 여러 Executor가 같은 판정을 쓰도록 한 곳에 모은다.
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    /**
     * ieum-agent가 내려주는 {@code errorCode} → 실패 분류.
     *
     * <p>agent가 이 필드를 아직 안 내려주면(null) {@link FailureKind#UNKNOWN}이며 재시도하지 않는다.
     * 값은 agent {@code common/error_code.py}의 {@code ErrorCode} 이름과 1:1이다.
     */
    private static final Map<String, FailureKind> AGENT_ERROR_CODES = Map.of(
        "RATE_LIMITED", FailureKind.RATE_LIMIT,
        "AGENT_TIMEOUT", FailureKind.TIMEOUT,
        // 도구 미호출·프롬프트 문제 등 같은 입력이면 같은 결과가 나오는 실패
        "AGENT_TOOL_NOT_CALLED", FailureKind.CLIENT_ERROR,
        "MISSING_CREDENTIAL", FailureKind.CLIENT_ERROR,
        // LLM 호출 자체가 실패했으나 원인이 특정되지 않음 — 반복해도 같을 가능성이 높다
        "AGENT_EXECUTION_FAILED", FailureKind.UNKNOWN,
        // 아래 둘은 agent 어휘가 아니라, agent 서비스가 본문 없이 HTTP 오류만 준 경우
        // BE가 상태 코드로부터 합성하는 코드다(AgentNodeExecutor.agentServiceErrorCode).
        "AGENT_SERVICE_ERROR", FailureKind.SERVER_ERROR,
        "AGENT_BAD_REQUEST", FailureKind.CLIENT_ERROR
    );

    public static FailureKind fromAgentErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return FailureKind.UNKNOWN;
        }
        return AGENT_ERROR_CODES.getOrDefault(
            errorCode.trim().toUpperCase(Locale.ROOT), FailureKind.UNKNOWN);
    }

    /** HTTP 상태 코드 → 실패 분류. 429는 재시도 가치가 있고 나머지 4xx는 반복해도 같은 결과다. */
    public static FailureKind fromHttpStatus(int status) {
        if (status == 429) {
            return FailureKind.RATE_LIMIT;
        }
        if (status == 408) {
            return FailureKind.TIMEOUT;
        }
        if (status >= 500) {
            return FailureKind.SERVER_ERROR;
        }
        if (status >= 400) {
            return FailureKind.CLIENT_ERROR;
        }
        return FailureKind.UNKNOWN;
    }

    /**
     * 전송 계층 예외 → 실패 분류.
     *
     * <p>reactor {@code .block()}과 RestTemplate이 원인 예외를 여러 겹으로 감싸므로
     * cause 체인을 따라 내려가며 판정한다.
     */
    public static FailureKind fromException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return FailureKind.TIMEOUT;
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return FailureKind.NETWORK;
            }
            if (t instanceof IllegalArgumentException) {
                // URL 검증 실패·미지원 메서드 등 설정 오류 — 반복해도 같다
                return FailureKind.CLIENT_ERROR;
            }
        }
        return FailureKind.UNKNOWN;
    }
}
