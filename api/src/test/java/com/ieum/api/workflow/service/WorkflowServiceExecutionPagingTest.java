package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceExecutionPagingTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private WorkflowExecutionRunner workflowExecutionRunner;
    @Mock private ExecutionEventPublisher executionEventPublisher;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private WorkflowService workflowService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    /**
     * size + 1개를 돌려주는 서비스 스텁용 실행 목록.
     *
     * <p>WorkflowExecutionResponse.from()이 getWorkflow().getId()와 getWorkflowVersion().getId()를
     * 타므로 두 연관 mock을 반드시 스텁해야 NPE가 나지 않는다. 초과분 1개는 limit(size)에 잘려
     * 매핑되지 않으므로 strict stubbing에 걸리지 않도록 lenient()로 스텁한다.
     */
    private List<WorkflowExecution> executions(int count) {
        List<WorkflowExecution> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorkflowExecution e = mock(WorkflowExecution.class);
            Workflow w = mock(Workflow.class);
            WorkflowVersion v = mock(WorkflowVersion.class);
            lenient().when(e.getId()).thenReturn(UUID.randomUUID());
            lenient().when(e.getWorkflow()).thenReturn(w);
            lenient().when(w.getId()).thenReturn(workflowId);
            lenient().when(e.getWorkflowVersion()).thenReturn(v);
            lenient().when(v.getId()).thenReturn(UUID.randomUUID());
            lenient().when(e.getStatus()).thenReturn(ExecutionStatus.SUCCESS);
            lenient().when(e.getTriggerType()).thenReturn(TriggerType.MANUAL);
            lenient().when(e.getStartedAt()).thenReturn(LocalDateTime.now());
            list.add(e);
        }
        return list;
    }

    @Test
    @DisplayName("size + 1개가 조회되면 hasNext=true 이고 결과는 size개로 잘린다")
    void getExecutions_hasNext() {
        // executions(3) 호출(내부에서 mock 생성 + when() 스텁)을 given(...).willReturn(...) 인자로
        // 인라인하면, given()이 이미 시작한 스텁이 끝나기 전에 새 when()이 끼어들어
        // UnfinishedStubbingException이 난다 — 지역변수로 먼저 평가를 끝낸다.
        List<WorkflowExecution> executions = executions(3);
        given(workflowExecutionService.listExecutions(eq(workflowId), any(), any(), any(), eq(0), eq(2)))
            .willReturn(executions);

        PageResponse<WorkflowExecutionResponse> page =
            workflowService.getExecutions(userId, workflowId, null, null, null, null, 2);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.isHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo("1");
    }

    @Test
    @DisplayName("정확히 size개면 hasNext=false 이고 nextCursor는 null")
    void getExecutions_noNext() {
        List<WorkflowExecution> executions = executions(2);
        given(workflowExecutionService.listExecutions(eq(workflowId), any(), any(), any(), eq(0), eq(2)))
            .willReturn(executions);

        PageResponse<WorkflowExecutionResponse> page =
            workflowService.getExecutions(userId, workflowId, null, null, null, null, 2);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.isHasNext()).isFalse();
        assertThat(page.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("status·기간 필터가 조회 계층으로 그대로 전달된다")
    void getExecutions_passesFilters() {
        LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 26, 0, 0);
        given(workflowExecutionService.listExecutions(
                workflowId, ExecutionStatus.FAILED, from, to, 0, 20))
            .willReturn(List.of());

        PageResponse<WorkflowExecutionResponse> page = workflowService.getExecutions(
            userId, workflowId, ExecutionStatus.FAILED, from, to, null, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.isHasNext()).isFalse();
    }

    @Test
    @DisplayName("cursor가 음수면 INVALID_CURSOR (offset 음수로 인한 500 방지)")
    void getExecutions_negativeCursor_throwsInvalidCursor() {
        assertThatThrownBy(() ->
            workflowService.getExecutions(userId, workflowId, null, null, null, "-1", 20))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("cursor가 빈 문자열이면 첫 페이지로 처리한다")
    void getExecutions_blankCursor_firstPage() {
        given(workflowExecutionService.listExecutions(workflowId, null, null, null, 0, 20))
            .willReturn(List.of());

        PageResponse<WorkflowExecutionResponse> page =
            workflowService.getExecutions(userId, workflowId, null, null, null, "", 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.isHasNext()).isFalse();
    }
}
