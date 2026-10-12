package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.validation.WorkflowValidationClient;
import com.ieum.workflowcore.engine.validation.WorkflowValidationException;
import com.ieum.workflowcore.engine.validation.WorkflowValidationResult;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 워크플로우 최신 저장본의 정적 검증(agent) — 검증 엔드포인트와 수동 실행 사전 검사.
 *
 * <p>{@code @Transactional}을 걸지 않는다 — agent 호출(최대 3초)을 트랜잭션에 묶지 않기 위해서다. 저장소 조회는
 * {@link WorkflowCrudService}의 각자 readOnly 트랜잭션으로 끝난다. 소유권 확인이 agent 호출보다 먼저다.
 */
@Service
@RequiredArgsConstructor
public class WorkflowValidationService {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowValidationClient validationClient;

    /** 검증 엔드포인트. agent에 못 닿으면 결과를 지어내지 않고 502. */
    public WorkflowValidationResult validate(UUID userId, UUID workflowId) {
        WorkflowDefinitionDocument definition = loadLatestDefinition(userId, workflowId);
        return validationClient.validate(definition.getNodes(), definition.getEdges())
            .orElseThrow(() -> new CustomException(ErrorCode.PROVIDER_ERROR,
                "검증 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    /** 수동 실행 전 — 에러가 있으면 400, agent 장애·타임아웃이면 통과(fail-open, 런타임이 다시 본다). */
    public void assertExecutable(UUID userId, UUID workflowId) {
        WorkflowDefinitionDocument definition = loadLatestDefinition(userId, workflowId);
        validationClient.validate(definition.getNodes(), definition.getEdges())
            .filter(WorkflowValidationResult::hasErrors)
            .ifPresent(result -> {
                throw new WorkflowValidationException(result.errors());
            });
    }

    private WorkflowDefinitionDocument loadLatestDefinition(UUID userId, UUID workflowId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId);
        WorkflowVersion latest = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));
        return workflowCrudService.loadDefinition(latest);
    }
}
