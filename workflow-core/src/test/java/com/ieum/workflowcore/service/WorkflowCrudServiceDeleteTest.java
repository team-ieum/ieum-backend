package com.ieum.workflowcore.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.repository.NodeTestSampleRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import com.ieum.workflowcore.scheduler.WorkflowScheduler;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 노드 테스트 샘플은 workflow_id FK를 가지는데 DB cascade가 없다 — 워크플로우 행을 지우기 전에
 * 샘플을 먼저 지우지 않으면 삭제가 FK 위반(500)이 된다. 삭제 경로는 둘(사용자·스케줄러 정리)이고
 * 둘 다 {@code deleteWorkflowInternal}을 지난다.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowCrudServiceDeleteTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowVersionRepository workflowVersionRepository;
    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Mock private WorkflowScheduler workflowScheduler;
    @Mock private WorkflowDefinitionRepository definitionRepository;
    @Mock private NodeTestSampleRepository nodeTestSampleRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private WorkflowCrudService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private Workflow workflow() {
        Workflow workflow = Workflow.builder().userId(userId).name("w").isActive(true).build();
        ReflectionTestUtils.setField(workflow, "id", workflowId);
        return workflow;
    }

    @Test
    @DisplayName("사용자 삭제 — 샘플이 워크플로우 행보다 먼저 지워진다")
    void userDelete_removesSamplesBeforeWorkflow() {
        Workflow workflow = workflow();
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(workflow));

        runInTransaction(() -> service.deleteWorkflow(userId, workflowId));

        InOrder order = inOrder(nodeTestSampleRepository, workflowRepository);
        order.verify(nodeTestSampleRepository).deleteByWorkflow(workflow);
        order.verify(workflowRepository).delete(workflow);
    }

    @Test
    @DisplayName("스케줄러 자동 정리 — 같은 경로라 샘플이 먼저 지워진다")
    void schedulerCleanup_removesSamplesBeforeWorkflow() {
        Workflow workflow = workflow();
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(workflow));

        runInTransaction(() -> service.deleteWorkflowById(workflowId));

        InOrder order = inOrder(nodeTestSampleRepository, workflowRepository);
        order.verify(nodeTestSampleRepository).deleteByWorkflow(workflow);
        order.verify(workflowRepository).delete(workflow);
    }

    /** afterCommit 콜백 등록에 트랜잭션 동기화가 필요하다 — WorkflowCrudServiceScheduleTest와 같은 방식. */
    private void runInTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
