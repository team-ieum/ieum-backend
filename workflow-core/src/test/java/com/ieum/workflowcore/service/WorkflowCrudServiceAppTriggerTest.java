package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.NodeTestSampleRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import com.ieum.workflowcore.scheduler.WorkflowScheduler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class WorkflowCrudServiceAppTriggerTest {

    private static final String GMAIL_NODES =
        "[{\"id\":\"t1\",\"type\":\"TRIGGER\",\"config\":{\"triggerType\":\"GMAIL_NEW_EMAIL\",\"query\":\"from:x\"}}]";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowVersionRepository workflowVersionRepository;
    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Mock private NodeTestSampleRepository nodeTestSampleRepository;
    @Mock private WorkflowScheduler workflowScheduler;
    @Mock private WorkflowDefinitionRepository definitionRepository;
    @Mock private AppTriggerSubscriptionService appTriggerSubscriptionService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private WorkflowCrudService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private Workflow workflow(boolean active) {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(active)
            .triggerType(TriggerType.GMAIL_NEW_EMAIL).build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        return w;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> syncedNodes() {
        ArgumentCaptor<List<Map<String, Object>>> nodes = ArgumentCaptor.forClass(List.class);
        verify(appTriggerSubscriptionService).sync(any(Workflow.class), nodes.capture());
        return nodes.getValue();
    }

    @Test
    @DisplayName("생성 — 파싱한 노드로 sync, Mongo 저장보다 먼저")
    void createSyncsBeforeMongoSave() {
        given(definitionRepository.save(any(WorkflowDefinitionDocument.class))).willAnswer(inv -> inv.getArgument(0));

        runInTransaction(() -> service.createWorkflow(userId, "w", null, GMAIL_NODES, "[]",
            TriggerType.GMAIL_NEW_EMAIL, null));

        assertThat(syncedNodes()).singleElement().satisfies(n -> assertThat(n.get("id")).isEqualTo("t1"));
        InOrder order = inOrder(appTriggerSubscriptionService, definitionRepository);
        order.verify(appTriggerSubscriptionService).sync(any(), any());
        order.verify(definitionRepository).save(any());
    }

    @Test
    @DisplayName("생성 — 검증 거부면 예외 그대로, Mongo 문서를 만들지 않는다(고아 방지)")
    void createRejectedLeavesNoMongoDoc() {
        willThrow(new CustomException(ErrorCode.INVALID_INPUT, "레포 접근 불가"))
            .given(appTriggerSubscriptionService).sync(any(), any());

        assertThatThrownBy(() -> runInTransaction(() -> service.createWorkflow(userId, "w", null, GMAIL_NODES, "[]",
            TriggerType.GMAIL_NEW_EMAIL, null))).isInstanceOf(CustomException.class);
        verify(definitionRepository, never()).save(any());
    }

    @Test
    @DisplayName("REST 수정 — 저장될 노드로 sync")
    void updateSyncs() {
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(workflow(true)));
        given(definitionRepository.save(any(WorkflowDefinitionDocument.class))).willAnswer(inv -> inv.getArgument(0));

        runInTransaction(() -> service.updateWorkflow(userId, workflowId, "w", null, GMAIL_NODES, "[]",
            TriggerType.GMAIL_NEW_EMAIL, null));

        assertThat(syncedNodes()).hasSize(1);
    }

    @Test
    @DisplayName("AI 저장 — 트리거 노드가 없어 triggerType이 null이어도 sync(=행 삭제 판단)")
    void agentSaveSyncsEvenWithoutTriggerType() {
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(workflow(true)));
        given(definitionRepository.save(any(WorkflowDefinitionDocument.class))).willAnswer(inv -> inv.getArgument(0));

        runInTransaction(() -> service.saveAgentVersion(workflowId, "[]", "[]", null, null));

        assertThat(syncedNodes()).isEmpty();
    }

    @Test
    @DisplayName("활성화 — 저장된 최신 버전 노드로 sync")
    void activateSyncsLatestNodes() {
        Workflow w = workflow(false);
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(w));
        WorkflowVersion version = WorkflowVersion.builder().workflow(w).version(1).mongoDefinitionId("m1").build();
        given(workflowVersionRepository.findFirstByWorkflowIdOrderByVersionDesc(workflowId)).willReturn(Optional.of(version));
        given(definitionRepository.findById("m1")).willReturn(Optional.of(WorkflowDefinitionDocument.builder()
            .nodes(List.of(Map.of("id", "t1", "type", "TRIGGER", "config", Map.of("triggerType", "GMAIL_NEW_EMAIL"))))
            .edges(List.of()).build()));

        runInTransaction(() -> service.activateWorkflow(userId, workflowId));

        verify(appTriggerSubscriptionService).sync(eq(w), eq(List.of(
            Map.of("id", "t1", "type", "TRIGGER", "config", Map.of("triggerType", "GMAIL_NEW_EMAIL")))));
    }

    @Test
    @DisplayName("활성화 — 앱 트리거가 아니면 Mongo를 읽지 않는다(정의 문서 없는 기존 워크플로우도 활성화 성공)")
    void activateNonAppTriggerSkipsMongo() {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(false)
            .triggerType(TriggerType.SCHEDULE).cronExpression("0 0 9 * * ?").build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(w));

        runInTransaction(() -> service.activateWorkflow(userId, workflowId));

        assertThat(w.isActive()).isTrue();
        verify(appTriggerSubscriptionService).sync(w, List.of());
        verify(workflowVersionRepository, never()).findFirstByWorkflowIdOrderByVersionDesc(any());
        verify(definitionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("비활성화 — 빈 노드로 sync(=삭제)")
    void deactivateSyncsEmpty() {
        Workflow w = workflow(true);
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(w));

        runInTransaction(() -> service.deactivateWorkflow(userId, workflowId));

        verify(appTriggerSubscriptionService).sync(w, List.of());
    }

    @Test
    @DisplayName("삭제 — 구독 행을 워크플로우 행보다 먼저 지운다")
    void deleteRemovesSubscriptions() {
        Workflow w = workflow(true);
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(w));

        runInTransaction(() -> service.deleteWorkflow(userId, workflowId));

        InOrder order = inOrder(appTriggerSubscriptionService, workflowRepository);
        order.verify(appTriggerSubscriptionService).deleteByWorkflowId(workflowId);
        order.verify(workflowRepository).delete(w);
    }

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
