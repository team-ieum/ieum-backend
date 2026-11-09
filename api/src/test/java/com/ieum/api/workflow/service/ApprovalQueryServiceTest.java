package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.ApprovalGateDetailResponse;
import com.ieum.api.workflow.dto.ApprovalGateSummaryResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.repository.ApprovalGateRepository.OpenGate;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalQueryServiceTest {

    @Mock private ApprovalGateRepository approvalGateRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ApprovalQueryService service;

    private final UUID userId = UUID.randomUUID();
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        workflow = Workflow.builder().userId(userId).name("결재").isActive(true).build();
        ReflectionTestUtils.setField(workflow, "id", UUID.randomUUID());
        given(workflowRepository.findById(workflow.getId())).willReturn(Optional.of(workflow));
        given(workflowRepository.findAllById(any())).willReturn(List.of(workflow));
    }

    private ApprovalGate gate(UUID owner, String message) throws Exception {
        String view = objectMapper.writeValueAsString(Map.of("event", "approval.request", "message", message));
        ApprovalGate g = ApprovalGate.open(UUID.randomUUID(), workflow.getId(), owner, "g1", "approval.request",
            view, message, null, LocalDateTime.now().plusHours(1), null);
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        given(approvalGateRepository.findById(g.getId())).willReturn(Optional.of(g));
        return g;
    }

    private OpenGate openGate(String title) {
        UUID id = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        return new OpenGate() {
            @Override public UUID getId() { return id; }
            @Override public UUID getExecutionId() { return executionId; }
            @Override public UUID getWorkflowId() { return workflow.getId(); }
            @Override public String getNodeId() { return "g1"; }
            @Override public String getEvent() { return "approval.request"; }
            @Override public String getTitle() { return title; }
            @Override public LocalDateTime getDeadline() { return now.plusHours(1); }
            @Override public LocalDateTime getCreatedAt() { return now; }
        };
    }

    @Test
    @DisplayName("남의 게이트·없는 게이트 상세는 404 APPROVAL_GATE_NOT_FOUND, 내 게이트는 view를 그대로")
    void 남의_게이트_상세는_404() throws Exception {
        ApprovalGate others = gate(UUID.randomUUID(), "남의 것");
        ApprovalGate mine = gate(userId, "내 것");

        assertThatThrownBy(() -> service.detail(userId, others.getId()))
            .isInstanceOf(CustomException.class).extracting("errorCode")
            .isEqualTo(ErrorCode.APPROVAL_GATE_NOT_FOUND);
        assertThatThrownBy(() -> service.detail(userId, UUID.randomUUID()))
            .isInstanceOf(CustomException.class).extracting("errorCode")
            .isEqualTo(ErrorCode.APPROVAL_GATE_NOT_FOUND);

        ApprovalGateDetailResponse detail = service.detail(userId, mine.getId());
        assertThat(detail.getView()).containsEntry("message", "내 것");
        assertThat(detail.getWorkflowName()).isEqualTo("결재");
    }

    @Test
    @DisplayName("상세는 실행 상태를 싣는다 — 거절(멈춤)로 닫힌 실행의 남은 게이트는 decision이 null이어도 처리할 수 없다")
    void 상세_실행상태를_싣는다() throws Exception {
        ApprovalGate open = gate(userId, "대기");
        ApprovalGate closed = gate(userId, "닫힘");
        given(workflowExecutionRepository.findById(open.getExecutionId()))
            .willReturn(Optional.of(execution(ExecutionStatus.WAITING_APPROVAL)));
        given(workflowExecutionRepository.findById(closed.getExecutionId()))
            .willReturn(Optional.of(execution(ExecutionStatus.FAILED)));

        assertThat(service.detail(userId, open.getId()).getExecutionStatus()).isEqualTo("WAITING_APPROVAL");
        ApprovalGateDetailResponse detail = service.detail(userId, closed.getId());
        assertThat(detail.getDecision()).isNull();
        assertThat(detail.getExecutionStatus()).isEqualTo("FAILED");
    }

    private WorkflowExecution execution(ExecutionStatus status) {
        return WorkflowExecution.builder().workflow(workflow).status(status)
            .triggerType(TriggerType.MANUAL).startedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("목록 size는 50이 상한이고 size+1개를 읽어 hasNext를 본다. 커서는 페이지 번호")
    void 목록_size_상한_50() throws Exception {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        given(approvalGateRepository.findOpenByUser(eq(userId), page.capture())).willReturn(List.of());

        service.list(userId, null, 100);
        assertThat(page.getValue().getPageSize()).isEqualTo(51);
        assertThat(page.getValue().getOffset()).isZero();

        service.list(userId, "2", 20);
        assertThat(page.getValue().getPageSize()).isEqualTo(21);
        assertThat(page.getValue().getOffset()).isEqualTo(40);

        assertThatThrownBy(() -> service.list(userId, "abc", 20))
            .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("목록 행은 워크플로우 이름과 title 컬럼을 싣고(view_json을 읽지 않음), 초과분이 있으면 다음 커서를 준다")
    void 목록_행_모양() throws Exception {
        List<OpenGate> rows = new ArrayList<>();
        rows.add(openGate("가".repeat(200)));
        rows.add(openGate("둘째"));
        given(approvalGateRepository.findOpenByUser(eq(userId), any())).willReturn(rows);

        PageResponse<ApprovalGateSummaryResponse> result = service.list(userId, null, 1);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.isHasNext()).isTrue();
        assertThat(result.getNextCursor()).isEqualTo("1");
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("가".repeat(200));
        assertThat(result.getContent().get(0).getWorkflowName()).isEqualTo("결재");
        verify(workflowRepository).findAllById(any());
    }
}
