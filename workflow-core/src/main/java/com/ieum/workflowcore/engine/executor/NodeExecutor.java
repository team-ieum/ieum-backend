package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.RetryPolicy;
import java.time.Duration;
import java.util.Map;

/**
 * 노드 타입별 실행 전략 인터페이스.
 * 구현체는 {@code @Component}로 등록하면 {@code SyncExecutionRuntime}이
 * {@code @PostConstruct}에서 {@code getNodeType()} 기준으로 자동 수집한다.
 */
public interface NodeExecutor {

    /** MARKER 마커의 timeoutMs 미선언 시 기본 TTL(5분) */
    Duration DEFAULT_MARKER_TTL = Duration.ofMinutes(5);
    /** MARKER 마커 TTL = timeoutMs + 이 여유 시간 */
    Duration MARKER_TTL_MARGIN = Duration.ofSeconds(30);

    /** 이 Executor가 처리하는 노드 타입 */
    NodeType getNodeType();

    /**
     * 노드를 실행하고 결과를 반환한다.
     *
     * @param node   실행할 노드 (config에 변수 치환이 완료된 값이 담겨 있음)
     * @param input  변수 치환이 완료된 노드 입력값 (node.config의 복사본)
     * @param cursor 현재 실행 컨텍스트 (이전 노드 출력 참조용)
     * @return 실행 결과 (success, output, errorMessage, durationMs)
     */
    ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) throws Exception;

    /**
     * 회차·멱등성 키 정보를 포함해 노드를 실행한다. 외부 호출(HTTP·AI)이 멱등성 가드를 적용해야
     * 할 때만 override한다. {@code cursor}는 워커 스레드 간 공유 객체라 attempt 정보를 담을 수
     * 없어 별도 파라미터로 전달한다 — {@code ExecutionCursor}/{@code ExecutionContext}에 넣지 말 것.
     *
     * @param attempt 이번 시도의 회차·멱등성 키·재시도 정책
     */
    default ExecutorResult execute(Node node, Map<String, Object> input,
                                   ExecutionCursor cursor, NodeAttempt attempt) throws Exception {
        return execute(node, input, cursor);
    }

    /**
     * 노드 1회 실행 시도의 부가 정보.
     *
     * @param attempt        1부터 시작하는 시도 회차 (모델 fallback 등 회차 의존 로직이 사용)
     * @param idempotencyKey {@link com.ieum.workflowcore.engine.IdempotencyKeys}로 생성된 키.
     *                       회차와 무관하게 노드당 고정값이다
     * @param policy         이 노드의 재시도 정책(멱등성 모드 포함)
     */
    record NodeAttempt(int attempt, String idempotencyKey, RetryPolicy policy) {
        /** 멱등성 가드가 적용되지 않는 기본 시도(3-인자 {@code execute} 호출용) */
        public static final NodeAttempt NONE = new NodeAttempt(1, null, null);
    }

    /** MARKER 마커 TTL: 정책에 {@code timeoutMs}가 있으면 그 값 + 여유, 없으면 기본값. */
    static Duration markerTtl(RetryPolicy policy) {
        Long timeoutMs = policy.timeoutMs();
        return timeoutMs == null ? DEFAULT_MARKER_TTL : Duration.ofMillis(timeoutMs).plus(MARKER_TTL_MARGIN);
    }
}
