package com.ieum.api.trigger;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 앱 트리거 이벤트 1건 → 실행 1건. Gmail 스윕러와 GitHub webhook(BE-81)이 공유한다. 사용자 컨텍스트 없이 돈다 —
 * {@code WorkflowScheduleJob}과 같은 순서(활성 재확인 → 최신 버전 → prepareExecution → 큐).
 *
 * <p>트랜잭션을 걸지 않는다 — prepareExecution이 자체 트랜잭션으로 커밋된 뒤 러너가 큐에 넣어야 워커가 행을 읽는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppTriggerFirer {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionRunner workflowExecutionRunner;

    /**
     * @param output 정규화 출력 — triggerData로 암호화 저장되고 트리거 노드 출력이 된다. 로그에 남기지 않는다
     * @return 실행을 만들었으면 true. 비활성·삭제·버전 없음이면 false
     * @throws com.ieum.common.exception.CustomException 그 사이 비활성화되면 prepareExecution의 INVALID_WORKFLOW
     */
    public boolean fire(UUID workflowId, TriggerType type, Map<String, Object> output) {
        Workflow workflow = workflowCrudService.findActiveById(workflowId);
        if (workflow == null) {
            return false;
        }
        Optional<WorkflowVersion> version = workflowCrudService.findLatestVersion(workflowId);
        if (version.isEmpty()) {
            return false;
        }
        WorkflowExecution execution = workflowExecutionService.prepareExecution(workflow, version.get(), type, output);
        workflowExecutionRunner.run(version.get(), execution.getId(), output);
        log.info("[AppTriggerFirer] 앱 트리거 발화 — type: {}, workflowId: {}, executionId: {}",
            type, workflowId, execution.getId());
        return true;
    }
}
