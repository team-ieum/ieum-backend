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
import com.ieum.workflowcore.service.NodeTestRunner;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
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
    private final WebhookListenStore webhookListenStore;
    private final NodeTestRunner nodeTestRunner;

    // @Transactional 미사용 의도:
    // prepareExecution()이 자체 트랜잭션으로 커밋된 뒤 @Async 런너가 실행되어야
    // 비동기 스레드에서 detached entity merge 오류를 방지할 수 있다.
    /**
     * @return 실행 응답. 노드 테스트가 이 워크플로우의 webhook 수신을 기다리던 중이었고 워크플로우가 비활성이거나
     *     WEBHOOK 트리거가 아니면 샘플만 저장하고 실행하지 않으므로 {@code null}이다(컨트롤러는 data 없는 202).
     */
    public WorkflowExecutionResponse trigger(UUID workflowId, WebhookTriggerRequest request) {
        Workflow workflow = workflowCrudService.getWorkflowById(workflowId);

        Map<String, Object> payload = (request != null && request.getPayload() != null)
            ? request.getPayload()
            : Collections.emptyMap();

        // 노드 테스트가 첫 수신을 기다리는 중이면 이 요청이 그 1건이다 — 소비는 원자적이라 동시 요청 중 하나만 받는다.
        boolean sampled = captureListenSample(workflow, payload);
        boolean runnable = workflow.isActive() && workflow.getTriggerType() == TriggerType.WEBHOOK;
        if (sampled && !runnable) {
            // active·triggerType 검사 우회는 listen 창 안 1건으로 한정한다 — 샘플만 남기고 실행하지 않는다.
            log.info("[WebhookService] 테스트 샘플만 저장 — workflowId: {}", workflowId);
            return null;
        }

        if (!workflow.isActive()) {
            throw new CustomException(ErrorCode.WORKFLOW_NOT_ACTIVE);
        }
        if (workflow.getTriggerType() != TriggerType.WEBHOOK) {
            throw new CustomException(ErrorCode.WEBHOOK_TRIGGER_MISMATCH);
        }

        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));

        WorkflowExecution execution = workflowExecutionService.prepareExecution(
            workflow, latestVersion, TriggerType.WEBHOOK, payload);

        workflowExecutionRunner.run(latestVersion, execution.getId(), payload);

        log.info("[WebhookService] Webhook 비동기 실행 시작 — workflowId: {}, executionId: {}",
            workflowId, execution.getId());

        return WorkflowExecutionResponse.from(execution);
    }

    /**
     * listen 키를 소비해 샘플을 남긴다. 샘플 저장 실패가 실제 웹훅 실행을 막으면 안 된다 — 키는 이미 소비돼
     * FE는 TTL까지 기다리다 "수신 없음"으로 보게 되지만, 그쪽이 실행 유실보다 낫다. 페이로드는 로그에 싣지 않는다.
     */
    private boolean captureListenSample(Workflow workflow, Map<String, Object> payload) {
        Optional<String> listeningNodeId = webhookListenStore.consume(workflow.getId());
        if (listeningNodeId.isEmpty()) {
            return false;
        }
        try {
            nodeTestRunner.recordWebhookSample(workflow, listeningNodeId.get(), payload);
            return true;
        } catch (Exception e) {
            log.warn("[WebhookService] 테스트 샘플 저장 실패 — workflowId: {}", workflow.getId(), e);
            return false;
        }
    }
}
