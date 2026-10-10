package com.ieum.api.workflow.service;

import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhook.service.WebhookListenStore;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.NodeTestRequest;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.service.NodeTestRunner;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 노드 단일 테스트의 API 계층 — 소유자 검사, body 노드 가드, webhook 대기 분기를 맡고 실행·샘플 저장은
 * workflow-core {@link NodeTestRunner}에 위임한다.
 *
 * <p>{@code @Transactional}을 달지 않는다 — 에이전트·외부 API 호출을 하나의 트랜잭션에 묶지 않기 위해서다
 * (저장소 호출은 각자 트랜잭션). OSIV(기본 on)라 DB 커넥션 자체는 요청 끝까지 유지된다.
 */
@Service
@RequiredArgsConstructor
public class NodeTestService {

    private final WorkflowCrudService workflowCrudService;
    private final NodeTestRunner nodeTestRunner;
    private final CredentialService credentialService;
    private final WebhookListenStore webhookListenStore;

    public NodeTestResponse test(UUID userId, UUID workflowId, String nodeId, NodeTestRequest request) {
        // 소유자 검사가 무엇보다 먼저다 — 남의 워크플로우는 존재 여부도 드러내지 않는 404.
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        Node node = resolveNode(userId, workflowId, nodeId, request);

        if (listensForWebhook(workflow, node)) {
            LocalDateTime expiresAt = webhookListenStore.start(workflowId, nodeId);
            return NodeTestResponse.listening(workflowId, expiresAt);
        }
        return NodeTestResponse.from(nodeTestRunner.run(workflow, node, request.getInput()));
    }

    public NodeTestResponse getSample(UUID userId, UUID workflowId, String nodeId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId);
        return nodeTestRunner.findSample(workflowId, nodeId)
            .map(NodeTestResponse::from)
            .orElseThrow(() -> new CustomException(ErrorCode.TEST_SAMPLE_NOT_FOUND));
    }

    private Node resolveNode(UUID userId, UUID workflowId, String nodeId, NodeTestRequest request) {
        NodeDto draft = request.getNode();
        if (draft == null) {
            return nodeTestRunner.findSavedNode(workflowId, nodeId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "저장된 워크플로우에 해당 노드가 없습니다."));
        }
        if (!nodeId.equals(draft.getId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로의 nodeId와 body의 node.id가 다릅니다.");
        }
        Map<String, Object> config = draft.getConfig() == null ? new HashMap<>() : draft.getConfig();
        rejectUnsafeConfig(userId, config);
        return new Node(draft.getId(), draft.getType(), draft.getLabel(), config);
    }

    /**
     * 저장 경로({@code WorkflowService.rejectForeignCredentialIds·rejectRawWebhookUrls})와 같은 세 판정.
     * body 노드는 저장을 거치지 않고 곧바로 실행되므로 여기서 막지 않으면 남의 크레덴셜·평문 비밀이 실행에 닿는다.
     */
    private void rejectUnsafeConfig(UUID userId, Map<String, Object> config) {
        Set<String> owned = credentialService.getByUserId(userId).stream()
            .map(credential -> credential.getId().toString())
            .collect(Collectors.toSet());
        NodeCredentialGuard.rejectForeignCredentialId(config, owned);
        NodeCredentialGuard.rejectInlineSecret(config);
        RawWebhookUrlGuard.rejectRawWebhookUrl(config);
    }

    /** 노드 config의 triggerType이 우선, 비어 있으면 워크플로우 최상위 triggerType(예: 노드 config가 {}인 WEBHOOK 워크플로우). */
    private static boolean listensForWebhook(Workflow workflow, Node node) {
        if (node.getType() != NodeType.TRIGGER) {
            return false;
        }
        Object declared = node.getConfig() == null ? null : node.getConfig().get("triggerType");
        String effective = declared instanceof String s && !s.isBlank() ? s : workflow.getTriggerType().name();
        return "WEBHOOK".equalsIgnoreCase(effective);
    }
}
