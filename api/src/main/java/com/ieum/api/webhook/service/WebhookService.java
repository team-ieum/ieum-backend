package com.ieum.api.webhook.service;

import com.ieum.api.webhook.dto.WebhookTriggerRequest;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Webhook 트리거 비즈니스 로직.
 *
 * <p>인증 없이 외부 시스템이 호출하는 엔드포인트이므로, JWT 인증 대신
 * workflowId + WEBHOOK 트리거 타입 + 활성 상태 검증으로 실행 권한을 확인한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionRunner workflowExecutionRunner;

    // @Transactional 미사용 의도:
    // prepareExecution()이 자체 트랜잭션으로 커밋된 뒤 @Async 런너가 실행되어야
    // 비동기 스레드에서 detached entity merge 오류를 방지할 수 있다.
    public WorkflowExecutionResponse trigger(UUID workflowId, WebhookTriggerRequest request) {
        Workflow workflow = workflowCrudService.getWorkflowById(workflowId);

        if (!workflow.isActive()) {
            throw new CustomException(ErrorCode.WORKFLOW_NOT_ACTIVE);
        }
        if (workflow.getTriggerType() != TriggerType.WEBHOOK) {
            throw new CustomException(ErrorCode.WEBHOOK_TRIGGER_MISMATCH);
        }

        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));

        WorkflowExecution execution = workflowExecutionService.prepareExecution(
            workflow, latestVersion, TriggerType.WEBHOOK);

        Map<String, Object> payload = (request != null && request.getPayload() != null)
            ? request.getPayload()
            : Collections.emptyMap();

        workflowExecutionRunner.run(latestVersion, execution.getId(), payload);

        log.info("[WebhookService] Webhook 비동기 실행 시작 — workflowId: {}, executionId: {}",
            workflowId, execution.getId());

        return WorkflowExecutionResponse.from(execution);
    }
}
