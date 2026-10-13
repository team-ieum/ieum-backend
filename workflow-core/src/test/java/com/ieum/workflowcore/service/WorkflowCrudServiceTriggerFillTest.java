package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.NodeTestSampleRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import com.ieum.workflowcore.repository.WorkflowWebhookTokenRepository;
import com.ieum.workflowcore.scheduler.WorkflowScheduler;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 트리거 노드 config에 triggerType이 없고 최상위가 WEBHOOK이면 저장 시점에 채운다 — 실행기·listen·샘플이 같은 값을 본다. */
@ExtendWith(MockitoExtension.class)
class WorkflowCrudServiceTriggerFillTest {

    private static final String EMPTY_CONFIG = "[{\"id\":\"t1\",\"type\":\"TRIGGER\",\"config\":{}}]";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowVersionRepository workflowVersionRepository;
    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Mock private NodeTestSampleRepository nodeTestSampleRepository;
    @Mock private WorkflowScheduler workflowScheduler;
    @Mock private WorkflowDefinitionRepository definitionRepository;
    @Mock private AppTriggerSubscriptionService appTriggerSubscriptionService;
    @Mock private WorkflowWebhookTokenRepository webhookTokenRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private WorkflowCrudService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedTriggerConfig() {
        ArgumentCaptor<WorkflowDefinitionDocument> doc = ArgumentCaptor.forClass(WorkflowDefinitionDocument.class);
        verify(definitionRepository).save(doc.capture());
        return (Map<String, Object>) doc.getValue().getNodes().get(0).get("config");
    }

    private void stubSave() {
        given(definitionRepository.save(any(WorkflowDefinitionDocument.class))).willAnswer(inv -> inv.getArgument(0));
    }

    private void inTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("생성 — config {} + 최상위 WEBHOOK이면 저장된 config.triggerType이 WEBHOOK")
    void createFillsWebhook() {
        stubSave();
        service.createWorkflow(userId, "w", null, EMPTY_CONFIG, "[]", TriggerType.WEBHOOK, null);
        assertThat(savedTriggerConfig()).containsEntry("triggerType", "WEBHOOK").doesNotContainKey("cron");
    }

    @Test
    @DisplayName("생성 — 최상위 MANUAL(기본값)이면 채우지 않는다")
    void createManualNotFilled() {
        stubSave();
        service.createWorkflow(userId, "w", null, EMPTY_CONFIG, "[]", null, null);
        assertThat(savedTriggerConfig()).doesNotContainKey("triggerType");
    }

    @Test
    @DisplayName("생성 — 노드에 triggerType이 이미 있으면 보존한다")
    void createKeepsDeclared() {
        stubSave();
        service.createWorkflow(userId, "w", null,
            "[{\"id\":\"t1\",\"type\":\"TRIGGER\",\"config\":{\"triggerType\":\"MANUAL\"}}]", "[]",
            TriggerType.WEBHOOK, null);
        assertThat(savedTriggerConfig()).containsEntry("triggerType", "MANUAL");
    }

    @Test
    @DisplayName("생성 — 최상위 SCHEDULE이면 채우지 않는다 — Quartz cron이 노드에 들어가면 agent 5필드 검증에 걸린다")
    void createScheduleNotFilled() {
        stubSave();
        inTransaction(() -> service.createWorkflow(userId, "w", null, EMPTY_CONFIG, "[]",
            TriggerType.SCHEDULE, "0 0 9 * * ?"));
        assertThat(savedTriggerConfig()).doesNotContainKey("triggerType").doesNotContainKey("cron");
    }

    @Test
    @DisplayName("REST 수정 — config {} + 최상위 WEBHOOK이면 채운다")
    void updateFillsWebhook() {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(true).triggerType(TriggerType.MANUAL).build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        given(workflowRepository.findByIdAndUserId(workflowId, userId)).willReturn(Optional.of(w));
        stubSave();
        inTransaction(() -> service.updateWorkflow(userId, workflowId, "w", null, EMPTY_CONFIG, "[]",
            TriggerType.WEBHOOK, null));
        assertThat(savedTriggerConfig()).containsEntry("triggerType", "WEBHOOK");
    }

    @Test
    @DisplayName("AI 저장 — 노드 선언이 없어 triggerType null이면 저장된 최상위 WEBHOOK으로 채운다")
    void agentSaveFillsStoredTopLevel() {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(true).triggerType(TriggerType.WEBHOOK).build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(w));
        stubSave();
        service.saveAgentVersion(workflowId, EMPTY_CONFIG, "[]", null, null);
        assertThat(savedTriggerConfig()).containsEntry("triggerType", "WEBHOOK");
    }

    @Test
    @DisplayName("앱 트리거는 채우지 않는다 — 노드 선언 필수 규칙(sync 400)을 우회하지 않게")
    void appTriggerNotFilled() {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(false)
            .triggerType(TriggerType.GMAIL_NEW_EMAIL).build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(w));
        stubSave();
        service.saveAgentVersion(workflowId, EMPTY_CONFIG, "[]", null, null);
        assertThat(savedTriggerConfig()).doesNotContainKey("triggerType");
    }
}
