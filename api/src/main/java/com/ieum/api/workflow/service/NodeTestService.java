package com.ieum.api.workflow.service;

import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhook.service.WebhookListenStore;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.NodeTestRequest;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.NodeDerivation;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.service.NodeTestResult;
import com.ieum.workflowcore.service.NodeTestRunner;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.TriggerHandler;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 노드 단일 테스트의 API 계층 — 소유자 검사, body 노드 가드, 트리거 노드의 테스트 방식 분기를 맡고 실행·샘플 저장은
 * workflow-core {@link NodeTestRunner}에 위임한다. 테스트 방식은 노드 event의 트리거 핸들러가 정한다
 * ({@link TriggerHandler#nodeTestMode()}) — 새 트리거를 추가해도 이 클래스는 고치지 않는다.
 *
 * <p>{@code @Transactional}을 달지 않는다 — 에이전트·외부 API 호출을 하나의 트랜잭션에 묶지 않기 위해서다
 * (저장소 호출은 각자 트랜잭션). OSIV(기본 on)라 DB 커넥션 자체는 요청 끝까지 유지된다.
 */
@Service
@RequiredArgsConstructor
public class NodeTestService {

    private static final String NO_RECENT_ITEM = "최근 항목이 없습니다";

    // 필드 순서 = 생성자 인자 순서(@RequiredArgsConstructor) — 테스트가 직접 호출한다
    private final WorkflowCrudService workflowCrudService;
    private final NodeTestRunner nodeTestRunner;
    private final CredentialService credentialService;
    private final WebhookListenStore webhookListenStore;
    private final WorkflowWebhookTokenService webhookTokenService;
    private final TriggerHandlerRegistry triggerHandlers;
    private final NodeDerivation nodeDerivation;
    private final EventCatalog eventCatalog;

    public NodeTestResponse test(UUID userId, UUID workflowId, String nodeId, NodeTestRequest request) {
        // 소유자 검사가 무엇보다 먼저다 — 남의 워크플로우는 존재 여부도 드러내지 않는 404.
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        Node node = resolveNode(userId, workflowId, nodeId, request);
        TriggerType trigger = triggerTypeOf(node);
        // event가 없거나 트리거 event가 아니면 러너로 바로 실행한다
        TriggerHandler handler = trigger == null ? null : triggerHandlers.get(trigger);
        NodeTestMode mode = handler == null ? NodeTestMode.RUN : handler.nodeTestMode();

        return switch (mode) {
            case LISTEN -> listen(workflowId, nodeId);
            case FETCH -> NodeTestResponse.from(fetchSample(workflow, node, handler));
            case RUN -> NodeTestResponse.from(nodeTestRunner.run(workflow, node, request.getInput()));
        };
    }

    /** 실행하지 않고 토큰 주소 수신을 기다린다. 수신 주소는 토큰 주소뿐이다 — 토큰이 없으면 listen해도 받을 길이 없다(R15) */
    private NodeTestResponse listen(UUID workflowId, String nodeId) {
        if (webhookTokenService.status(workflowId).isEmpty()) {
            throw new CustomException(ErrorCode.WEBHOOK_TOKEN_NOT_ISSUED);
        }
        return NodeTestResponse.listening(webhookListenStore.start(workflowId, nodeId));
    }

    /**
     * listen하지 않고 최근 항목 1건을 즉시 샘플로 만든다(spec §4.3). 그 1건을 러너에 페이로드로 넘기면 런타임과 같은 경로
     * (핸들러 {@code output})로 샘플 = 정규화 출력이 된다. 조회 거부는 HTTP 오류가 아니라 FAILED 샘플이다(테스트는 수행됐다 —
     * 다른 노드의 실패와 같은 규칙).
     */
    private NodeTestResult fetchSample(Workflow workflow, Node node, TriggerHandler handler) {
        Optional<Map<String, Object>> latest;
        try {
            latest = handler.fetchSample(workflow.getUserId(), node.getConfig());
        } catch (CustomException e) {
            return nodeTestRunner.recordFailure(workflow, node.getId(), e.getMessage());
        }
        return latest.isEmpty()
            ? nodeTestRunner.recordFailure(workflow, node.getId(), NO_RECENT_ITEM)
            : nodeTestRunner.run(workflow, node, latest.get());
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
        // 저장 경로와 같은 파생이다 — 모르는 event는 400, type은 event가 정한다(요청 type 무시) (IEUM-BE-92).
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", draft.getId());
        raw.put("event", draft.getEvent());
        raw.put("config", draft.getConfig());
        Map<String, Object> derived = nodeDerivation.derive(raw);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) derived.get("config");
        rejectUnsafeConfig(userId, config);
        return new Node(draft.getId(), NodeType.valueOf((String) derived.get("type")), draft.getLabel(),
            config, draft.getEvent());
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

    /** 노드 event의 카탈로그 trigger_type. event가 없거나 트리거 event가 아니면 null — 워크플로우 PG triggerType으로 폴백하지 않는다. */
    private TriggerType triggerTypeOf(Node node) {
        return eventCatalog.triggerTypeOf(node.getEvent()).orElse(null);
    }
}
