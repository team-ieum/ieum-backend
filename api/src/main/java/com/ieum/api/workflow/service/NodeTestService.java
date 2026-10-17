package com.ieum.api.workflow.service;

import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.trigger.AppTriggerSampleFetcher;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 노드 단일 테스트의 API 계층 — 소유자 검사, body 노드 가드, webhook 대기 분기, 앱 트리거 즉시 샘플 분기
 * ({@link AppTriggerSampleFetcher}로 최근 항목 1건 조회)를 맡고 실행·샘플 저장은 workflow-core {@link NodeTestRunner}에 위임한다.
 *
 * <p>{@code @Transactional}을 달지 않는다 — 에이전트·외부 API 호출을 하나의 트랜잭션에 묶지 않기 위해서다
 * (저장소 호출은 각자 트랜잭션). OSIV(기본 on)라 DB 커넥션 자체는 요청 끝까지 유지된다.
 */
@Service
public class NodeTestService {

    private static final String NO_RECENT_ITEM = "최근 항목이 없습니다";
    private static final String UNSUPPORTED_APP_TRIGGER = "이 트리거는 아직 테스트할 수 없습니다";

    private final WorkflowCrudService workflowCrudService;
    private final NodeTestRunner nodeTestRunner;
    private final CredentialService credentialService;
    private final WebhookListenStore webhookListenStore;
    private final WorkflowWebhookTokenService webhookTokenService;
    private final NodeDerivation nodeDerivation;
    private final EventCatalog eventCatalog;
    private final Map<TriggerType, AppTriggerSampleFetcher> sampleFetchers = new EnumMap<>(TriggerType.class);

    public NodeTestService(WorkflowCrudService workflowCrudService, NodeTestRunner nodeTestRunner,
                           CredentialService credentialService, WebhookListenStore webhookListenStore,
                           WorkflowWebhookTokenService webhookTokenService,
                           List<AppTriggerSampleFetcher> sampleFetchers,
                           NodeDerivation nodeDerivation, EventCatalog eventCatalog) {
        this.workflowCrudService = workflowCrudService;
        this.nodeTestRunner = nodeTestRunner;
        this.credentialService = credentialService;
        this.webhookListenStore = webhookListenStore;
        this.webhookTokenService = webhookTokenService;
        this.nodeDerivation = nodeDerivation;
        this.eventCatalog = eventCatalog;
        // 같은 triggerType 공급원이 둘이면 부팅에서 막는다 — EnumMap.put이 한쪽을 조용히 덮는다
        sampleFetchers.forEach(f -> {
            if (this.sampleFetchers.put(f.triggerType(), f) != null) {
                throw new IllegalStateException("앱 트리거 샘플 공급원 중복: " + f.triggerType());
            }
        });
    }

    public NodeTestResponse test(UUID userId, UUID workflowId, String nodeId, NodeTestRequest request) {
        // 소유자 검사가 무엇보다 먼저다 — 남의 워크플로우는 존재 여부도 드러내지 않는 404.
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        Node node = resolveNode(userId, workflowId, nodeId, request);
        TriggerType trigger = triggerTypeOf(node);

        if (trigger == TriggerType.WEBHOOK) {
            // 수신 주소는 토큰 주소뿐이다 — 토큰이 없으면 listen해도 받을 길이 없다(R15)
            if (webhookTokenService.status(workflowId).isEmpty()) {
                throw new CustomException(ErrorCode.WEBHOOK_TOKEN_NOT_ISSUED);
            }
            return NodeTestResponse.listening(webhookListenStore.start(workflowId, nodeId));
        }
        if (trigger != null && trigger.isAppTrigger()) {
            return NodeTestResponse.from(testAppTrigger(workflow, node, trigger));
        }
        return NodeTestResponse.from(nodeTestRunner.run(workflow, node, request.getInput()));
    }

    /**
     * 앱 트리거는 listen하지 않고 최근 항목 1건을 즉시 샘플로 만든다(spec §4.3). 그 1건을 러너에 페이로드로 넘기면
     * 런타임과 같은 경로({@code TriggerNodeExecutor} 앱 분기)로 샘플 = 정규화 출력이 된다. 조회 거부는 HTTP 오류가 아니라
     * FAILED 샘플이다(테스트는 수행됐다 — 다른 노드의 실패와 같은 규칙).
     */
    private NodeTestResult testAppTrigger(Workflow workflow, Node node, TriggerType type) {
        AppTriggerSampleFetcher fetcher = sampleFetchers.get(type);
        if (fetcher == null) {
            return nodeTestRunner.recordFailure(workflow, node.getId(), UNSUPPORTED_APP_TRIGGER);
        }
        Optional<Map<String, Object>> latest;
        try {
            latest = fetcher.fetchLatest(workflow.getUserId(), node.getConfig());
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
