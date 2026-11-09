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
            view, null, LocalDateTime.now().plusHours(1), null);
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        given(approvalGateRepository.findById(g.getId())).willReturn(Optional.of(g));
        return g;
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
    @DisplayName("목록 행은 워크플로우 이름과 message 앞 200자 제목을 싣고, 초과분이 있으면 다음 커서를 준다")
    void 목록_행_모양() throws Exception {
        List<ApprovalGate> rows = new ArrayList<>();
        rows.add(gate(userId, "가".repeat(300)));
        rows.add(gate(userId, "둘째"));
        given(approvalGateRepository.findOpenByUser(eq(userId), any())).willReturn(rows);

        PageResponse<ApprovalGateSummaryResponse> result = service.list(userId, null, 1);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.isHasNext()).isTrue();
        assertThat(result.getNextCursor()).isEqualTo("1");
        assertThat(result.getContent().get(0).getTitle()).hasSize(200);
        assertThat(result.getContent().get(0).getWorkflowName()).isEqualTo("결재");
        verify(workflowRepository).findAllById(any());
    }
}
