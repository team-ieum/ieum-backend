package com.ieum.workflowcore.engine.event;

/**
 * SSE 실행 이벤트가 싣는 노드 상태.
 *
 * <p>영속용 {@link com.ieum.workflowcore.domain.enums.ExecutionLogStatus}와 분리한 이유는
 * 이벤트에 결과가 아직 없는 진행 상태(PENDING·RUNNING)가 필요하기 때문이다. 그 값을
 * {@code ExecutionLogStatus}에 추가하면 {@code node_runs.status} 컬럼과 그 컬럼을 거르는
 * QueryDSL 조건이 "실행 결과"가 아닌 값을 담게 된다.
 *
 * <p>{@code SUCCESS}·{@code FAILED}·{@code SKIPPED}는 {@code ExecutionLogStatus}와 이름이
 * 같다. 프론트가 이미 그 문자열을 읽고 있어 직렬화 결과가 바뀌면 안 되므로 의도적으로 맞춘 것이다
 * ({@code ExecutionEventSerializationTest}가 이 대응을 검증한다).
 */
public enum NodeEventStatus {
    /** 실행 대기 — 아직 시작되지 않은 노드 */
    PENDING,
    /** 실행 중 */
    RUNNING,
    /** 실행 성공 */
    SUCCESS,
    /** 실행 실패 */
    FAILED,
    /** 실행하지 않고 건너뜀 */
    SKIPPED
}
