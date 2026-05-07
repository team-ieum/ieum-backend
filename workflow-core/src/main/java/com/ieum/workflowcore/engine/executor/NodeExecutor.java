package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.util.Map;

/**
 * 노드 타입별 실행 전략 인터페이스.
 * 구현체는 {@code @Component}로 등록하면 {@code SyncExecutionRuntime}이
 * {@code @PostConstruct}에서 {@code getNodeType()} 기준으로 자동 수집한다.
 */
public interface NodeExecutor {

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
}
