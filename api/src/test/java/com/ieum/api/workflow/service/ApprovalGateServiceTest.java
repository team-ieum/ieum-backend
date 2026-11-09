package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.ApprovalGateConfig;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalGateServiceTest {

    @Mock private ApprovalGateRepository approvalGateRepository;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private ExecutionRetryService executionRetryService;
    @Mock private UserRepository userRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ApprovalGateService service;
    @Captor private ArgumentCaptor<Map<String, Object>> output;

    private final UUID userId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final List<ApprovalGate> gates = new ArrayList<>();

    private Workflow workflow;
    private WorkflowVersion version;
    private WorkflowExecution execution;
    private WorkflowExecution continuation;

    @BeforeEach
    void setUp() {
        workflow = Workflow.builder().userId(userId).name("승인").isActive(true).build();
        ReflectionTestUtils.setField(workflow, "id", UUID.randomUUID());
        version = mock(WorkflowVersion.class);
        execution = execution(executionId, ExecutionStatus.WAITING_APPROVAL);
        continuation = execution(UUID.randomUUID(), ExecutionStatus.PENDING);

        given(workflowExecutionService.lockExecutionWithVersion(executionId)).willReturn(execution);
        given(approvalGateRepository.findByExecutionIdOrderByCreatedAtAsc(executionId)).willReturn(gates);
        given(workflowCrudService.getWorkflowByOwner(userId, workflow.getId())).willReturn(workflow);
        given(executionRetryService.startContinuation(workflow, execution)).willReturn(continuation);
    }

    private WorkflowExecution execution(UUID id, ExecutionStatus status) {
        WorkflowExecution e = WorkflowExecution.builder()
            .workflow(workflow).workflowVersion(version).status(status)
            .triggerType(TriggerType.MANUAL).startedAt(LocalDateTime.now()).build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private ApprovalGate gate(String nodeId, String event, Map<String, Object> raw, LocalDateTime deadline)
            throws Exception {
        String view = objectMapper.writeValueAsString(ApprovalGateConfig.parse(event, raw).view());
        ApprovalGate g = ApprovalGate.open(executionId, workflow.getId(), userId, nodeId, event, view, null,
            deadline, null);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(g, "id", id);
        given(approvalGateRepository.findExecutionIdById(id)).willReturn(Optional.of(executionId));
        given(approvalGateRepository.findById(id)).willReturn(Optional.of(g));
        gates.add(g);
        return g;
    }

    private ApprovalGate request(String nodeId) throws Exception {
        return gate(nodeId, ApprovalGateConfig.REQUEST, Map.of(), LocalDateTime.now().plusHours(1));
    }

    private ApprovalGate collect(String nodeId) throws Exception {
        return gate(nodeId, ApprovalGateConfig.COLLECT, Map.of("fields", List.of(
            Map.of("key", "amount", "label", "금액", "type", "number", "required", true))),
            LocalDateTime.now().plusHours(1));
    }

    private Map<String, Object> outputOf(ApprovalGate g) throws Exception {
        return objectMapper.readValue(g.getOutputJson(), new TypeReference<>() {});
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
            .extracting("errorCode").isEqualTo(code);
    }

    @Test
    @DisplayName("병렬 게이트 둘 중 첫째 승인 — 실행은 승인 대기로 남고 이어진 실행·노드 행이 없다")
    void 첫_게이트_승인은_실행을_이어가지_않는다() throws Exception {
        ApprovalGate g1 = request("g1");
        request("g2");

        WorkflowExecutionResponse response = service.approve(userId, g1.getId(), null, null);

        assertThat(g1.getDecision()).isEqualTo("approved");
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);
        verify(executionRetryService, never()).startContinuation(any(), any());
        verify(workflowExecutionService, never()).recordGateDecision(any(), any(), any(), any(), any());
        assertThat(response.getId()).isEqualTo(executionId);
        assertThat(response.getPendingApprovalNodeIds()).containsExactly("g2");
    }

    @Test
    @DisplayName("마지막 게이트가 결정되면 게이트마다 자기 출력으로 노드 행을 쓰고 이어간다")
    void 마지막_게이트에서_게이트별_출력으로_이어간다() throws Exception {
        ApprovalGate g1 = request("g1");
        ApprovalGate g2 = collect("g2");
        service.approve(userId, g1.getId(), null, null);

        WorkflowExecutionResponse response = service.submit(userId, g2.getId(), Map.of("amount", "1,000"), "확인");

        verify(workflowExecutionService).recordGateDecision(
            eq(execution), eq("g1"), eq(ExecutionLogStatus.SUCCESS), output.capture(), isNull());
        assertThat(output.getValue()).containsEntry("decision", "approved").containsEntry("approved", true);
        verify(workflowExecutionService).recordGateDecision(
            eq(execution), eq("g2"), eq(ExecutionLogStatus.SUCCESS), output.capture(), isNull());
        Map<String, Object> second = output.getValue();
        assertThat(second).containsEntry("decision", "submitted").containsEntry("note", "확인")
            .containsEntry("approvedBy", userId.toString());
        assertThat(((Number) ((Map<?, ?>) second.get("values")).get("amount")).intValue()).isEqualTo(1000);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        verify(executionRetryService, times(1)).startContinuation(workflow, execution);
        assertThat(response.getId()).isEqualTo(continuation.getId());
        assertThat(response.getPendingApprovalNodeIds()).isEmpty();
    }

    @Test
    @DisplayName("거절(멈춤) — 그 게이트만 FAILED 행, 실행 FAILED, 알림 경로(markAsFailed) 없음")
    void 거절_멈춤은_그_게이트만_FAILED로_실행을_닫는다() throws Exception {
        ApprovalGate g1 = request("g1");
        ApprovalGate g2 = request("g2");

        service.reject(userId, g1.getId(), "아님");

        verify(workflowExecutionService, times(1)).recordGateDecision(any(), any(), any(), any(), any());
        verify(workflowExecutionService).recordGateDecision(
            eq(execution), eq("g1"), eq(ExecutionLogStatus.FAILED), output.capture(), eq("승인 거부: 아님"));
        assertThat(output.getValue()).containsEntry("rejectedBy", userId.toString())
            .containsEntry("reason", "아님").containsEntry("decision", "rejected");
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).isEqualTo("승인 거부: 아님");
        assertThat(g2.isDecided()).isFalse();
        verify(workflowExecutionService, never()).markAsFailed(any(), any());
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("거절(계속) — SUCCESS 행(decision rejected)으로 이어간다")
    void 거절_계속은_SUCCESS_결정으로_남는다() throws Exception {
        ApprovalGate g = gate("g", ApprovalGateConfig.REQUEST, Map.of("onReject", "continue"),
            LocalDateTime.now().plusHours(1));

        service.reject(userId, g.getId(), null);

        verify(workflowExecutionService).recordGateDecision(
            eq(execution), eq("g"), eq(ExecutionLogStatus.SUCCESS), output.capture(), isNull());
        assertThat(output.getValue()).containsEntry("decision", "rejected").containsEntry("approved", false)
            .doesNotContainKeys("reason", "note");
        verify(executionRetryService).startContinuation(workflow, execution);
    }

    @Test
    @DisplayName("기다리기와 함께 멈춘 실행 — 게이트가 남으면 DELAY 분기를 타지 않고, 마지막 게이트에서 기다리기로 넘긴다")
    void 게이트가_남으면_DELAY_분기를_타지_않는다() throws Exception {
        ReflectionTestUtils.setField(execution, "waitingDelayNodes",
            "{\"d\":{\"scheduledFor\":\"2026-11-09T10:40:00+09:00\"}}");
        ReflectionTestUtils.setField(execution, "resumeAt", LocalDateTime.now().plusHours(1));
        ApprovalGate g1 = request("g1");
        ApprovalGate g2 = request("g2");

        service.approve(userId, g1.getId(), null, null);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);

        WorkflowExecutionResponse response = service.approve(userId, g2.getId(), null, null);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.WAITING_DELAY);
        verify(executionRetryService, never()).startContinuation(any(), any());
        verify(workflowExecutionService, never()).recordDelayRelease(any(), any());
        assertThat(response.getId()).isEqualTo(executionId);
    }

    @Test
    @DisplayName("editable 게이트는 고친 내용이 출력 content가 된다. editable이 아니면 400")
    void 고친_내용이_출력이_된다() throws Exception {
        ApprovalGate editable = gate("g1", ApprovalGateConfig.REQUEST, Map.of("editable", true,
            "items", List.of(Map.of("key", "draft", "value", "초안"))), LocalDateTime.now().plusHours(1));
        ApprovalGate fixed = gate("g2", ApprovalGateConfig.REQUEST, Map.of(
            "items", List.of(Map.of("key", "draft", "value", "초안"))), LocalDateTime.now().plusHours(1));

        assertCode(() -> service.approve(userId, fixed.getId(), null, Map.of("draft", "고침")),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertThat(fixed.isDecided()).isFalse();

        service.approve(userId, editable.getId(), null, Map.of("draft", "고침"));
        assertThat(outputOf(editable).get("content")).isEqualTo(Map.of("draft", "고침"));
    }

    @Test
    @DisplayName("필수 칸이 빠진 제출은 400이고 게이트는 미결정 그대로다")
    void 값_입력_검사_실패는_400() throws Exception {
        ApprovalGate g = collect("g");

        assertCode(() -> service.submit(userId, g.getId(), Map.of(), null), ErrorCode.APPROVAL_INPUT_INVALID);
        assertThat(g.isDecided()).isFalse();
    }

    @Test
    @DisplayName("이미 처리된 게이트는 409")
    void 이미_처리된_게이트는_409() throws Exception {
        ApprovalGate g1 = request("g1");
        request("g2");
        service.approve(userId, g1.getId(), null, null);

        assertCode(() -> service.approve(userId, g1.getId(), null, null), ErrorCode.APPROVAL_GATE_ALREADY_DECIDED);
        assertThat(ErrorCode.APPROVAL_GATE_ALREADY_DECIDED.getStatus().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("남의 게이트·없는 게이트는 404")
    void 남의_게이트는_404() throws Exception {
        ApprovalGate g = request("g");

        assertCode(() -> service.approve(UUID.randomUUID(), g.getId(), null, null),
            ErrorCode.APPROVAL_GATE_NOT_FOUND);
        assertCode(() -> service.reject(userId, UUID.randomUUID(), null), ErrorCode.APPROVAL_GATE_NOT_FOUND);
        assertThat(ErrorCode.APPROVAL_GATE_NOT_FOUND.getStatus().value()).isEqualTo(404);
        assertThat(g.isDecided()).isFalse();
    }

    @Test
    @DisplayName("승인 요청에 submit, 값 입력받기에 approve·reject는 409")
    void 동작_불일치는_409() throws Exception {
        ApprovalGate req = request("g1");
        ApprovalGate col = collect("g2");

        assertCode(() -> service.submit(userId, req.getId(), Map.of(), null), ErrorCode.APPROVAL_GATE_ACTION_MISMATCH);
        assertCode(() -> service.approve(userId, col.getId(), null, null), ErrorCode.APPROVAL_GATE_ACTION_MISMATCH);
        assertCode(() -> service.reject(userId, col.getId(), null), ErrorCode.APPROVAL_GATE_ACTION_MISMATCH);
        assertThat(ErrorCode.APPROVAL_GATE_ACTION_MISMATCH.getStatus().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("실행 ID만 먼저 읽고 실행을 잠근 뒤 게이트 엔티티를 읽는다 — 낡은 decision을 보지 않게")
    void 잠근_뒤_게이트를_다시_읽는다() throws Exception {
        ApprovalGate g = request("g");

        service.approve(userId, g.getId(), null, null);

        InOrder order = inOrder(approvalGateRepository, workflowExecutionService);
        order.verify(approvalGateRepository).findExecutionIdById(g.getId());
        order.verify(workflowExecutionService).lockExecutionWithVersion(executionId);
        order.verify(approvalGateRepository).findById(g.getId());
    }

    @Test
    @DisplayName("남은 게이트가 있으면 실행 승인 기한을 남은 게이트 기한의 최솟값으로 다시 잡는다")
    void 남은_게이트가_있으면_기한을_다시_잡는다() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ApprovalGate soon = gate("g1", ApprovalGateConfig.REQUEST, Map.of(), now.plusMinutes(30));
        ApprovalGate late = gate("g2", ApprovalGateConfig.REQUEST, Map.of(), now.plusHours(2));
        ReflectionTestUtils.setField(execution, "approvalDeadline", soon.getDeadline());

        service.approve(userId, soon.getId(), null, null);

        assertThat(execution.getApprovalDeadline()).isEqualTo(late.getDeadline());
    }

    @Test
    @DisplayName("content 값이 문자열이 아니거나 values 값이 중첩 Map·목록이면 400")
    void content_값이_문자열이_아니면_400() throws Exception {
        ApprovalGate req = gate("g1", ApprovalGateConfig.REQUEST, Map.of("editable", true,
            "items", List.of(Map.of("key", "draft", "value", "초안"))), LocalDateTime.now().plusHours(1));
        ApprovalGate col = collect("g2");

        assertCode(() -> service.approve(userId, req.getId(), null, Map.of("draft", Map.of("x", 1))),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.approve(userId, req.getId(), null, Map.of("draft", 1)),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.approve(userId, req.getId(), null, Map.of("other", "x")),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.approve(userId, req.getId(), null, Map.of("draft", "x".repeat(10_001))),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.submit(userId, col.getId(), Map.of("amount", List.of(1)), null),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertThat(ErrorCode.APPROVAL_INPUT_INVALID.getStatus().value()).isEqualTo(400);
        assertThat(req.isDecided()).isFalse();
        assertThat(col.isDecided()).isFalse();
    }

    @Test
    @DisplayName("레거시 거절 — 남은 게이트 중 stop이 하나라도 있으면 전부 FAILED 행 + 실행 FAILED")
    void 레거시_거절은_stop이_하나라도_있으면_실행을_끝낸다() throws Exception {
        gate("g1", ApprovalGateConfig.REQUEST, Map.of("onReject", "continue"), LocalDateTime.now().plusHours(1));
        request("g2");

        service.decideAll(userId, executionId, false, "아님");

        verify(workflowExecutionService, times(2)).recordGateDecision(
            eq(execution), anyString(), eq(ExecutionLogStatus.FAILED), any(), eq("승인 거부: 아님"));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).isEqualTo("승인 거부: 아님");
        verify(executionRetryService, never()).startContinuation(any(), any());
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
    }

    @Test
    @DisplayName("레거시 승인 — 남은 게이트 전부 승인해 이어간다. 남은 게이트에 값 입력받기가 있으면 409")
    void 레거시_승인은_남은_게이트_전부() throws Exception {
        ApprovalGate g1 = request("g1");
        ApprovalGate g2 = request("g2");

        WorkflowExecutionResponse response = service.decideAll(userId, executionId, true, null);

        assertThat(g1.getDecision()).isEqualTo("approved");
        assertThat(g2.getDecision()).isEqualTo("approved");
        verify(workflowExecutionService, times(2)).recordGateDecision(
            eq(execution), anyString(), eq(ExecutionLogStatus.SUCCESS), any(), isNull());
        verify(executionRetryService).startContinuation(workflow, execution);
        assertThat(response.getId()).isEqualTo(continuation.getId());

        gates.clear();
        ReflectionTestUtils.setField(execution, "status", ExecutionStatus.WAITING_APPROVAL);
        ApprovalGate r = request("r");
        collect("c");
        assertCode(() -> service.decideAll(userId, executionId, true, null),
            ErrorCode.APPROVAL_GATE_ACTION_MISMATCH);
        assertThat(r.isDecided()).isFalse();
    }

    @Test
    @DisplayName("거절(멈춤)로 실행이 FAILED면 응답의 대기 게이트는 빈 목록이다")
    void 거절_멈춤_응답은_대기_게이트가_없다() throws Exception {
        ApprovalGate g1 = request("g1");
        request("g2");

        WorkflowExecutionResponse response = service.reject(userId, g1.getId(), null);

        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(response.getPendingApprovalNodeIds()).isEmpty();
    }

    @Test
    @DisplayName("note가 200자를 넘으면 DTO를 거치지 않아도 400이고 게이트는 미결정 그대로다")
    void note_200자_넘으면_400() throws Exception {
        ApprovalGate req = request("g1");
        ApprovalGate col = collect("g2");
        String note = "가".repeat(201);

        assertCode(() -> service.approve(userId, req.getId(), note, null), ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.reject(userId, req.getId(), note), ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.submit(userId, col.getId(), Map.of("amount", 1), note),
            ErrorCode.APPROVAL_INPUT_INVALID);
        assertCode(() -> service.decideAll(userId, executionId, false, note), ErrorCode.APPROVAL_INPUT_INVALID);
        assertThat(req.isDecided()).isFalse();
        assertThat(col.isDecided()).isFalse();
    }

    @Test
    @DisplayName("만료(멈춤) — 승인 만료로 닫고 알림 경로(markAsFailed)를 탄다")
    void 만료_멈춤은_실패_알림() throws Exception {
        gate("g", ApprovalGateConfig.REQUEST, Map.of(), LocalDateTime.now().minusMinutes(1));

        service.expire(executionId, LocalDateTime.now());

        verify(workflowExecutionService).markAsFailed(executionId, "승인 만료", ExecutionStatus.WAITING_APPROVAL);
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("만료(계속) — decision timeout과 원래 content로 이어간다")
    void 만료_계속은_timeout으로_이어간다() throws Exception {
        gate("g", ApprovalGateConfig.REQUEST, Map.of("onTimeout", "continue",
            "items", List.of(Map.of("key", "draft", "value", "초안"))), LocalDateTime.now().minusMinutes(1));

        service.expire(executionId, LocalDateTime.now());

        verify(workflowExecutionService).recordGateDecision(
            eq(execution), eq("g"), eq(ExecutionLogStatus.SUCCESS), output.capture(), isNull());
        assertThat(output.getValue()).containsEntry("decision", "timeout").containsEntry("approved", false)
            .containsEntry("content", Map.of("draft", "초안"));
        verify(executionRetryService).startContinuation(workflow, execution);
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
    }

    @Test
    @DisplayName("만료(계속)인데 워크플로우가 꺼져 있으면 비활성 문구로 닫고 이어가지 않는다")
    void 만료_계속_비활성이면_닫는다() throws Exception {
        workflow.deactivate();
        gate("g", ApprovalGateConfig.REQUEST, Map.of("onTimeout", "continue"), LocalDateTime.now().minusMinutes(1));

        service.expire(executionId, LocalDateTime.now());

        verify(workflowExecutionService).markAsFailed(
            executionId, "워크플로우가 꺼져 있어 이어가지 않았어요", ExecutionStatus.WAITING_APPROVAL);
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("만료(계속)·비활성이어도 재개 시각이 미래인 기다리기는 막지 않고 기다리기로 넘긴다")
    void 만료_계속_비활성이어도_기다리기는_넘긴다() throws Exception {
        workflow.deactivate();
        ReflectionTestUtils.setField(execution, "waitingDelayNodes",
            "{\"d\":{\"scheduledFor\":\"2026-11-09T10:40:00+09:00\"}}");
        ReflectionTestUtils.setField(execution, "resumeAt", LocalDateTime.now().plusHours(1));
        gate("g", ApprovalGateConfig.REQUEST, Map.of("onTimeout", "continue"), LocalDateTime.now().minusMinutes(1));

        service.expire(executionId, LocalDateTime.now());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.WAITING_DELAY);
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("사용자 결정은 비활성 워크플로우여도 닫지 않는다 — 이어가기의 INVALID_WORKFLOW로 롤백된다")
    void 사용자_결정은_비활성이면_롤백된다() throws Exception {
        workflow.deactivate();
        given(executionRetryService.startContinuation(workflow, execution))
            .willThrow(new CustomException(ErrorCode.INVALID_WORKFLOW));
        ApprovalGate g = request("g");

        assertCode(() -> service.approve(userId, g.getId(), null, null), ErrorCode.INVALID_WORKFLOW);
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
    }

    @Test
    @DisplayName("일부 게이트만 만료면 timeout으로 결정하고 기한을 남은 게이트로 다시 잡는다")
    void 일부만_만료면_기한을_다시_계산() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ApprovalGate past = gate("g1", ApprovalGateConfig.REQUEST, Map.of("onTimeout", "continue"),
            now.minusMinutes(30));
        ApprovalGate later = gate("g2", ApprovalGateConfig.REQUEST, Map.of(), now.plusHours(2));
        ReflectionTestUtils.setField(execution, "approvalDeadline", past.getDeadline());

        service.expire(executionId, now);

        assertThat(past.getDecision()).isEqualTo("timeout");
        assertThat(later.isDecided()).isFalse();
        assertThat(execution.getApprovalDeadline()).isEqualTo(later.getDeadline());
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("기한 지난 게이트가 이미 처리됐으면 결정은 그대로 두고 기한만 다시 잡는다")
    void 만료_대상이_없으면_기한만_다시_잡는다() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ApprovalGate past = gate("g1", ApprovalGateConfig.REQUEST, Map.of(), now.minusMinutes(30));
        ApprovalGate later = gate("g2", ApprovalGateConfig.REQUEST, Map.of(), now.plusHours(2));
        past.decide("approved", "{}", now.minusMinutes(40));
        ReflectionTestUtils.setField(execution, "approvalDeadline", past.getDeadline());

        service.expire(executionId, now);

        assertThat(past.getDecision()).isEqualTo("approved");
        assertThat(later.isDecided()).isFalse();
        assertThat(execution.getApprovalDeadline()).isEqualTo(later.getDeadline());
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("게이트 행이 없는 레거시 실행은 기한이 지났을 때만 승인 만료로 닫는다")
    void 레거시_만료는_기한이_지났을_때만() {
        LocalDateTime now = LocalDateTime.now();
        ReflectionTestUtils.setField(execution, "approvalDeadline", now.plusMinutes(1));
        service.expire(executionId, now);
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());

        ReflectionTestUtils.setField(execution, "approvalDeadline", now.minusMinutes(1));
        service.expire(executionId, now);
        verify(workflowExecutionService).markAsFailed(executionId, "승인 만료", ExecutionStatus.WAITING_APPROVAL);
    }
}
