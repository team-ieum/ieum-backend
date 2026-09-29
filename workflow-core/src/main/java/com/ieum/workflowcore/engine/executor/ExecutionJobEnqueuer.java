package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * 준비된 실행(executionId)을 내구성 있는 잡 큐에 넣는 포트 인터페이스.
 * api 모듈의 어댑터(DefaultExecutionJobEnqueuer)가 Redis Stream 잡 큐({@code ExecutionJobQueue})로
 * 구현하여 주입된다.
 *
 * <p>workflow-core는 api 모듈(잡 큐·Redis)에 직접 의존하지 않으므로, 의존성 역전을 위해
 * 이 포트로 실행을 큐에 넘긴다. 큐에 들어간 실행은 워커가 꺼내 실행하고, 실행 도중
 * 프로세스가 죽어도 재시작 후 회수된다.
 */
public interface ExecutionJobEnqueuer {

    /**
     * 실행을 잡 큐에 넣는다.
     *
     * @param executionId {@code WorkflowExecutionService.prepareExecution()}으로 커밋된 실행 ID
     * @return 큐에 넣었으면 true. false면 아무도 이 실행을 꺼내지 않으므로 호출부가 직접
     *     실행해야 한다(유실 방지). 구현체는 큐 장애 시 예외 대신 false를 반환한다.
     */
    boolean enqueue(UUID executionId);
}
