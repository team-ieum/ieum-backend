package com.ieum.api.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.EdgeDto;
import com.ieum.api.workflow.dto.EdgeView;
import com.ieum.api.workflow.dto.ExecuteWorkflowRequest;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.NodeView;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionLogResponse;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.event.ExecutionEventSnapshot;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

/**
 * API 레이어 워크플로우 서비스.
 *
 * <p>Controller 요청을 받아 {@link WorkflowCrudService}, {@link WorkflowExecutionService}에
 * 위임하고, 결과를 DTO로 변환하여 반환하는 얇은 레이어다.
 * 비즈니스 로직은 workflow-core 서비스에 위치한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowService {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final ExecutionEventPublisher executionEventPublisher;
    private final ObjectMapper objectMapper;
    private final WebhookCredentialService webhookCredentialService;
    private final CredentialService credentialService;

    /**
     * 노드 config에서 {@code webhookCredentialId}를 찾을 때 들어가는 최대 중첩 깊이. 정의 문서의
     * 구조는 BE가 통제하지 못해 아주 깊거나 자기 참조하는 값이 올 수 있으므로, 방문 추적 대신
     * 깊이로 끊는다({@code SensitiveDataMasker}가 같은 이유로 쓰는 상한과 같은 값이다).
     * 이 아래의 참조는 이름 없이 남는다 — 별칭은 없어도 조회가 성립하는 값이라 여기서 닫아도 된다.
     */
    private static final int MAX_CONFIG_DEPTH = 20;

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public WorkflowResponse createWorkflow(UUID userId, CreateWorkflowRequest request) {
        List<Object> configs = request.getNodes().stream().<Object>map(NodeDto::getConfig).toList();
        rejectRawWebhookUrls(configs);
        rejectForeignCredentialIds(userId, configs);
        WorkflowVersion version = workflowCrudService.createWorkflow(
            userId,
            request.getName(),
            request.getDescription(),
            toJson(request.getNodes()),
            toJson(request.getEdges()),
            request.getTriggerType(),
            request.getCronExpression()
        );
        return toResponse(version.getWorkflow(), version, ownedWebhookNames(userId));
    }

    public PageResponse<WorkflowResponse> getWorkflows(UUID userId, String cursor, int size) {
        int page = parseCursor(cursor);
        List<Workflow> workflows = workflowCrudService.listWorkflows(userId, page, size);
        boolean hasNext = workflowCrudService.hasNextWorkflows(userId, page, size);

        List<UUID> workflowIds = workflows.stream().map(Workflow::getId).toList();
        Map<UUID, WorkflowVersion> versionMap = workflowCrudService.getLatestVersionMap(workflowIds);

        // 웹훅 별칭 사전은 페이지 전체에 한 번만 조회한다 — 목록의 워크플로우는 모두 같은 사용자
        // 소유라 사전도 하나면 충분하다. 워크플로우마다(또는 노드마다) 조회하면 N+1이 된다.
        Map<UUID, String> webhookNames = ownedWebhookNames(userId);

        List<WorkflowResponse> responses = workflows.stream()
            .map(w -> toResponse(w, versionMap.get(w.getId()), webhookNames))
            .toList();

        return PageResponse.of(responses, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    public WorkflowResponse getWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId).orElse(null);
        return toResponse(workflow, latestVersion, ownedWebhookNames(userId));
    }

    /**
     * 워크플로우를 수정한다. 요청이 보내지 않은 노드 필드는 직전 버전 값을 이어받는다 (IEUM-BE-65).
     *
     * <p>수정은 정의 전체를 새 버전으로 다시 쓰는 구조라, 요청 노드를 그대로 저장하면 클라이언트가
     * 보내지 않은 {@code description}·{@code position}이 소실된다. 그래서 직전 버전 정의 위에
     * 요청 노드를 덮어써({@link NodeDefinitionMerger}) 저장한다.
     */
    @Transactional
    public WorkflowResponse updateWorkflow(UUID userId, UUID workflowId,
            UpdateWorkflowRequest request) {
        // 소유권 검증이 이전 정의 조회보다 먼저다 — 남의 워크플로우 정의를 읽고 나서 거절하면 안 된다.
        Workflow current = workflowCrudService.getWorkflowByOwner(userId, workflowId);

        List<Map<String, Object>> mergedNodes = NodeDefinitionMerger.merge(
            previousNodes(workflowId), request.getNodes(), objectMapper);

        // 가드는 병합 결과에 돈다 — 요청이 config를 생략하면 이전 config가 되살아나는데, 그 안에 원문
        // 웹훅 URL이나 남의 credentialId가 있으면 요청만 검사해서는 통과해 버린다(IEUM-BE-62·64의
        // "저장되는 정의에는 원문 웹훅 URL·남의 크레덴셜이 없다"는 불변식이 깨진다).
        List<Object> configs = mergedNodes.stream().map(node -> node.get("config")).toList();
        rejectRawWebhookUrls(configs);
        rejectForeignCredentialIds(userId, configs);

        // 워크플로우 레벨 optional 필드도 노드와 같은 규칙이다 — 요청의 null은 "변경 없음"이라
        // 저장된 값을 그대로 넘긴다. 그대로 넘기면 triggerType이 MANUAL로 강등되고 cron이 지워져
        // Quartz Job까지 삭제된다(200만 돌아와 다음 실행이 없을 때까지 아무도 모른다).
        TriggerType triggerType = request.getTriggerType() != null
            ? request.getTriggerType() : current.getTriggerType();
        WorkflowVersion version = workflowCrudService.updateWorkflow(
            userId,
            workflowId,
            request.getName(),
            request.getDescription() != null ? request.getDescription() : current.getDescription(),
            toJson(mergedNodes),
            toJson(request.getEdges()),
            triggerType,
            resolveCronExpression(request.getCronExpression(), triggerType, current)
        );
        return toResponse(version.getWorkflow(), version, ownedWebhookNames(userId));
    }

    /**
     * 저장할 cron 표현식. 요청이 보낸 값이 우선이고, 생략했을 때만 저장된 값을 잇는다.
     *
     * <p>단 폴백은 유효 triggerType이 SCHEDULE일 때뿐이다 — {@code triggerType: "MANUAL"}로 스케줄을
     * 끄는 요청에서 저장된 cron을 되살리면 트리거는 MANUAL인데 cron만 남은 유령 값이 된다
     * ({@code validateScheduleConfig}는 SCHEDULE이 아니면 cron을 아예 보지 않아 걸러 주지 않는다).
     *
     * <p>저장된 트리거도 SCHEDULE이어야 한다. 그 유령 cron(MANUAL인데 cron이 남은 워크플로우)은 실제로
     * 만들어지므로, 이 조건이 없으면 {@code triggerType: "SCHEDULE"}만 보낸 요청이 사용자가 이번에
     * 지정한 적 없는 시각으로 Quartz Job을 등록한다. 여기서 {@code null}을 넘겨야 crud가 400으로 막는다.
     */
    private static String resolveCronExpression(String requested, TriggerType triggerType,
            Workflow current) {
        if (requested != null) {
            return requested;
        }
        boolean keepsSchedule = triggerType == TriggerType.SCHEDULE
            && current.getTriggerType() == TriggerType.SCHEDULE;
        return keepsSchedule ? current.getCronExpression() : null;
    }

    /**
     * 병합의 베이스가 될 직전 버전의 노드 목록. 버전이 없거나 정의 문서를 읽지 못하면 {@code null}이다.
     *
     * <p>정의 문서를 읽지 못해도 수정을 막지 않는다 — 병합 없이 요청 노드만 저장하는 예전 동작으로
     * 물러선다. 여기서 404를 던지면 Mongo 문서가 사라진 워크플로우는 수정으로 고칠 길조차 없어진다.
     */
    private List<Map<String, Object>> previousNodes(UUID workflowId) {
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
            .orElse(null);
        if (latestVersion == null) {
            return null;
        }
        try {
            return workflowCrudService.loadDefinition(latestVersion).getNodes();
        } catch (CustomException e) {
            log.warn("[WorkflowService] 직전 정의를 읽지 못해 병합 없이 저장 — workflowId: {}, errorCode: {}",
                workflowId, e.getErrorCode());
            return null;
        }
    }

    @Transactional
    public void deleteWorkflow(UUID userId, UUID workflowId) {
        workflowCrudService.deleteWorkflow(userId, workflowId);
    }

    @Transactional
    public WorkflowResponse activateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = workflowCrudService.activateWorkflow(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId).orElse(null);
        return toResponse(workflow, latestVersion, ownedWebhookNames(userId));
    }

    @Transactional
    public WorkflowResponse deactivateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = workflowCrudService.deactivateWorkflow(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId).orElse(null);
        return toResponse(workflow, latestVersion, ownedWebhookNames(userId));
    }

    // ------------------------------------------------------------------ EXECUTION

    // @Transactional 사용:
    // prepareExecution()은 REQUIRED로 이 트랜잭션에 참여한다.
    // TransactionSynchronizationManager.afterCommit()으로 커밋 완료 후 @Async 런너를 실행하여
    // Race Condition(findById 시점 레코드 미존재) 및 Detached Entity 문제를 방지한다.
    @Transactional
    public WorkflowExecutionResponse executeWorkflow(UUID userId, UUID workflowId,
            ExecuteWorkflowRequest request) {
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));

        Map<String, Object> triggerData = request.getTriggerData() != null
            ? request.getTriggerData()
            : Collections.emptyMap();

        WorkflowExecution execution = workflowExecutionService.prepareExecution(
            workflow, latestVersion, TriggerType.MANUAL, triggerData);

        final UUID executionId = execution.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    workflowExecutionRunner.run(latestVersion, executionId, triggerData);
                }
            }
        );

        return WorkflowExecutionResponse.from(execution);
    }

    public PageResponse<WorkflowExecutionResponse> getExecutions(UUID userId, UUID workflowId,
            ExecutionStatus status, LocalDateTime from, LocalDateTime to, String cursor, int size) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증

        int page = parseCursor(cursor);
        // size + 1개를 받아 초과분 존재로 hasNext를 판단한다 — 별도 count 쿼리가 필요 없다.
        List<WorkflowExecution> executions =
            workflowExecutionService.listExecutions(workflowId, status, from, to, page, size);
        boolean hasNext = executions.size() > size;

        List<WorkflowExecutionResponse> responses = executions.stream()
            .limit(size)
            .map(WorkflowExecutionResponse::from)
            .toList();

        return PageResponse.of(responses, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    public List<WorkflowExecutionLogResponse> getExecutionLogs(UUID userId, UUID workflowId,
            UUID executionId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증
        return workflowExecutionService.listExecutionLogs(workflowId, executionId).stream()
            .map(WorkflowExecutionLogResponse::from)
            .toList();
    }

    /**
     * 워크플로우 실행 진행 상황을 SSE 스트림으로 반환한다.
     *
     * <p>늦은 구독 보완을 위해 {@code WorkflowExecutionLog} 스냅샷(과거 이벤트)을 먼저 흘린 뒤,
     * 실행이 진행 중이면 라이브 이벤트({@link ExecutionEventPublisher})를 이어 붙인다.
     * 이미 종료된 실행은 스냅샷만 재생하고 스트림을 종료한다.
     */
    public Flux<ServerSentEvent<ExecutionEvent>> streamExecutionEvents(
            UUID userId, UUID workflowId, UUID executionId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증

        // 공유 sink 생성(subscribe) 전에 executionId가 이 workflowId에 속하는지 먼저 검증한다.
        // 권한 없는 요청이 공유 sink를 생성·오염시키거나, 정리 과정에서 실제 소유자의 라이브
        // 스트림을 끊는 것을 차단하기 위함이다.
        WorkflowExecution execution = workflowExecutionService.getExecution(executionId);
        if (!execution.getWorkflow().getId().equals(workflowId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        // 라이브 구독을 스냅샷 조회보다 먼저 등록(sink 생성)해, 스냅샷 조회 직후 발행되는
        // 이벤트가 누락되지 않게 한다. 검증 실패 시에는 구독자가 없을 때만 sink를 정리한다.
        Flux<ExecutionEvent> live = executionEventPublisher.subscribe(executionId);

        ExecutionEventSnapshot snapshot;
        try {
            snapshot = workflowExecutionService.loadEventSnapshot(workflowId, executionId);
        } catch (RuntimeException e) {
            executionEventPublisher.cleanUpIfNoSubscribers(executionId);
            throw e;
        }

        Flux<ExecutionEvent> events;
        if (snapshot.terminal()) {
            // 이미 종료된 실행 — 라이브 불필요. 다른 활성 구독자가 없을 때만 sink를 정리하고
            // 스냅샷만 재생한다(동시 구독 중인 정상 스트림을 끊지 않기 위함).
            executionEventPublisher.cleanUpIfNoSubscribers(executionId);
            events = Flux.fromIterable(snapshot.events());
        } else {
            // 진행 중 — 과거(스냅샷) → 미래(라이브) 연결. 경계 노드가 중복될 수 있으나
            // 프론트가 nodeId+type로 멱등 처리한다(누락보다 중복이 안전).
            events = Flux.fromIterable(snapshot.events()).concatWith(live);
        }

        return events.map(event -> ServerSentEvent.<ExecutionEvent>builder(event)
            .event(event.type().name())
            .build());
    }

    // ------------------------------------------------------------------ PRIVATE

    /**
     * 노드 {@code config.url}에 Slack·Discord 웹훅 URL 원문이 있으면 저장을 거부한다.
     *
     * <p>웹훅 URL은 그 자체가 비밀인데 노드 config는 Mongo에 평문으로 저장되고 조회 응답에도 그대로
     * 실린다. 대안은 이미 있다 — 웹훅 자격증명을 등록하고 {@code config.webhookCredentialId}로
     * 참조하면 {@code HttpNodeExecutor}가 실행 시점에만 복호해 쓴다(IEUM-BE-62 Task 1).
     *
     * <p><b>레거시도 거부한다.</b> 이미 원문 URL이 저장된 워크플로우는 그 값을 지우거나
     * {@code webhookCredentialId}로 바꾸기 전까지 이름만 고치는 수정도 저장할 수 없다 — 수정 요청은
     * 정의 전체를 새 버전으로 다시 쓰기 때문이다. 기존 값을 예외로 통과시키려면 "저장된 정의와 같은
     * URL인가"를 매 요청 비교해야 하는데, 베타 단계라 해당 워크플로우가 적을 것으로 보고 그 복잡도
     * 대신 단순한 쪽(항상 거부)을 택했다. 대가는 위의 수정 차단이다.
     *
     * <p>막는 것은 요청 본문의 리터럴 URL뿐이다. 변수 참조({@code {{nodes.x.output.url}}})처럼 실행
     * 시점에야 웹훅 URL이 되는 값이나, {@code url} 외의 config 필드(예: {@code body})에 숨긴 URL은
     * 여기서 걸리지 않는다.
     *
     * <p>판정과 메시지는 {@link RawWebhookUrlGuard}가 갖는다 — 채팅으로 agent가 만든 정의도 같은
     * 검사를 거치는데(Task 4) 둘이 각자 문구를 들고 있으면 같은 위반에 다른 반응이 나온다.
     */
    private void rejectRawWebhookUrls(List<Object> configs) {
        for (Object config : configs) {
            RawWebhookUrlGuard.rejectRawWebhookUrl(config);
        }
    }

    /**
     * 노드 {@code config.credentialId}가 요청자 소유가 아니면 저장을 거부한다 (IEUM-BE-64).
     *
     * <p>실행 시점의 복호화는 이미 소유자만 통과시키므로(Task 1) 이 검사가 없어도 남의 키가 새지는
     * 않는다. 다만 저장이 되면 실행에서야 {@code NOT_FOUND}로 죽어 사용자에게 원인이 보이지 않는다.
     *
     * <p>소유 목록 조회는 노드마다가 아니라 저장당 한 번이다 — 사용자당 최대 10개
     * ({@code CredentialService.MAX_CREDENTIALS_PER_USER})라 한 번 담아 두고 나눠 쓰면 충분하고,
     * 노드마다 부르면 N+1이 된다. 판정과 메시지는 {@link NodeCredentialGuard}가 갖는다(채팅으로
     * agent가 만든 정의도 같은 검사를 거친다).
     *
     * <p>같은 루프에서 비밀 원문 키도 거부한다({@code NodeCredentialGuard.rejectInlineSecret}) —
     * 크레덴셜 참조를 통째로 건너뛰고 API 키를 config에 박으면 소유 검사가 아무것도 보지 못한다.
     */
    private void rejectForeignCredentialIds(UUID userId, List<Object> configs) {
        if (configs.isEmpty()) {
            return;
        }
        Set<String> owned = credentialService.getByUserId(userId).stream()
            .map(credential -> credential.getId().toString())
            .collect(Collectors.toSet());
        for (Object config : configs) {
            NodeCredentialGuard.rejectForeignCredentialId(config, owned);
            // 남의 크레덴셜을 참조하는 것뿐 아니라, 참조 자체를 건너뛰고 원문을 config에 박는 길도 막는다.
            NodeCredentialGuard.rejectInlineSecret(config);
        }
    }


    /**
     * 저장된 정의(raw {@code Map})를 응답으로 옮긴다.
     *
     * <p>정의 문서의 구조는 BE가 통제하지 못한다(사유와 항목별 규칙은 {@link NodeView} 참조).
     * 그래서 응답 매핑은 요청 DTO({@link NodeDto}·{@link EdgeDto})와 Jackson 빈 바인딩을 쓰지 않고
     * {@link NodeView}·{@link EdgeView}가 raw 값을 직접 읽어 만든다 — 어긋난 문서 하나가 예외로
     * 터져 조회(특히 사용자의 모든 워크플로우를 한 번에 변환하는 {@link #getWorkflows})를 500으로
     * 무너뜨리지 않게 하기 위함이다.
     */
    private WorkflowResponse toResponse(Workflow workflow, WorkflowVersion version,
            Map<UUID, String> ownedWebhookNames) {
        List<NodeView> nodes = Collections.emptyList();
        List<EdgeView> edges = Collections.emptyList();
        if (version != null) {
            WorkflowDefinitionDocument definition = workflowCrudService.loadDefinition(version);
            nodes = NodeView.fromDefinition(definition.getNodes(), workflow.getId());
            edges = EdgeView.fromDefinition(definition.getEdges(), nodes, workflow.getId());
        }
        return WorkflowResponse.from(workflow, version, nodes, edges,
            webhookCredentialNames(nodes, ownedWebhookNames));
    }

    /**
     * 요청 사용자가 소유한 웹훅 자격증명의 별칭 사전(id → displayName).
     *
     * <p>소유자 검증이 이 조회 하나에 걸려 있다 — 사용자 것만 담기므로, 노드 config에 남의 자격증명
     * id가 박혀 있어도 아래 매핑에서 이름을 찾지 못한다. 사용자 단위로 한 번만 부르고 그 결과를
     * 응답 변환 전체가 나눠 쓴다(목록 조회의 N+1 방지).
     *
     * <p>{@code enabled}로 거르지 않는다 — {@code WebhookCredentialProvider.resolveWebhookUrl}이
     * 비활성 자격증명의 URL을 내주지 않는 것과 다른 판단이다. 비활성이라고 이름을 감추면 빌더에
     * UUID만 남아, 사용자가 어느 자격증명을 다시 켜야 하는지 알 수 없다. 이름은 URL과 달리 실행
     * 권한을 주지 않으므로 소유 여부만 걸러도 충분하다.
     *
     * <p>{@code Collectors.toMap} 대신 루프로 담는다 — 값이 null이면 그쪽은 NPE를 던지는데,
     * 이름 하나 때문에 조회 전체가 500이 되는 경로를 만들지 않기 위함이다
     * ({@code display_name}은 {@code not null}이라 지금은 나올 수 없는 값이다).
     */
    private Map<UUID, String> ownedWebhookNames(UUID userId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (WebhookCredential credential : webhookCredentialService.getByUserId(userId)) {
            if (credential.getId() != null && credential.getDisplayName() != null) {
                names.put(credential.getId(), credential.getDisplayName());
            }
        }
        return names;
    }

    /**
     * 노드 config가 참조하는 웹훅 자격증명 id에 별칭을 붙여 돌려준다(응답 전용 파생값 —
     * 저장된 정의에는 쓰지 않는다).
     *
     * <p>참조는 두 자리에 나타난다. HTTP 노드의 {@code config.webhookCredentialId}(IEUM-BE-62 Task 1)와
     * AI 노드의 {@code config.tools[].config.webhookCredentialId}({@code AgentNodeExecutor}가 실행
     * 시점에 읽는 자리)다. 자리마다 경로를 박아 두는 대신 config 전체를 훑는다 — 정의 문서는 BE를
     * 거치지 않는 저장 경로(ieum-agent 생성분)가 있어 중첩 위치가 늘어날 수 있는데, 그때 빌더에
     * UUID가 되돌아오는 것을 막기 위함이다.
     *
     * <p>어떤 입력에도 예외를 던지지 않는다. UUID로 읽히지 않는 값, 사용자 소유가 아닌 id, 삭제된
     * id는 모두 결과에 키가 없을 뿐이다 — 이름을 못 찾는 것이 조회를 500으로 만들면 안 된다.
     */
    private static Map<String, String> webhookCredentialNames(List<NodeView> nodes,
            Map<UUID, String> ownedWebhookNames) {
        if (ownedWebhookNames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (NodeView node : nodes) {
            collectWebhookNames(node.config(), 0, ownedWebhookNames, names);
        }
        return names;
    }

    private static void collectWebhookNames(Object value, int depth,
            Map<UUID, String> ownedWebhookNames, Map<String, String> names) {
        if (depth > MAX_CONFIG_DEPTH) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            putIfOwned(map.get("webhookCredentialId"), ownedWebhookNames, names);
            for (Object child : map.values()) {
                collectWebhookNames(child, depth + 1, ownedWebhookNames, names);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectWebhookNames(item, depth + 1, ownedWebhookNames, names);
            }
        }
    }

    /**
     * 사용자 소유 자격증명일 때만 별칭을 싣는다.
     *
     * <p>키는 config에 적힌 문자열 그대로다 — 프론트가 config에서 읽은 값을 그대로 키로 쓰면
     * 맞도록. 반면 소유 판정은 {@code UUID.fromString}으로 정규화한 값으로 한다
     * ({@code HttpNodeExecutor}가 같은 값을 해석하는 방식과 맞춘 것: 앞뒤 공백을 버리고 대소문자를
     * 구분하지 않는다).
     */
    private static void putIfOwned(Object rawId, Map<UUID, String> ownedWebhookNames,
            Map<String, String> names) {
        if (rawId == null || rawId instanceof Map || rawId instanceof List) {
            return;
        }
        String key = rawId.toString();
        UUID credentialId;
        try {
            credentialId = UUID.fromString(key.trim());
        } catch (IllegalArgumentException e) {
            // id 자리에 웹훅 URL을 붙여 넣은 경우가 정확히 이 분기다 — 값을 로그에 싣지 않는다.
            return;
        }
        String displayName = ownedWebhookNames.get(credentialId);
        if (displayName != null) {
            names.put(key, displayName);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "워크플로우 데이터 직렬화 실패");
        }
    }

    private int parseCursor(String cursor) {
        // 빈 문자열은 첫 페이지로 본다 — FE가 커서 파라미터를 빈 값으로 초기화해 보내는 경우가 흔하다.
        if (cursor == null || cursor.isBlank()) return 0;
        int page;
        try {
            page = Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
        if (page < 0) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
        return page;
    }
}
