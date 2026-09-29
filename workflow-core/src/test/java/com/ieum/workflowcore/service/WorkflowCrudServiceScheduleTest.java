package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 저장되는 cron이 유효 triggerType과 어긋나지 않는지 본다 (IEUM-BE-65).
 *
 * <p>고정하는 계약은 하나다 — 유효 {@code triggerType}이 SCHEDULE이 아니면 요청이 cron을 실어
 * 보내도 저장되지 않는다. 트리거는 MANUAL인데 cron만 남은 유령 값은 조회에는 보이면서 아무것도
 * 실행하지 않고({@code validateScheduleConfig}가 SCHEDULE이 아니면 cron을 보지 않아 400도 안 난다),
 * 나중에 SCHEDULE로 켜는 요청이 사용자가 지정한 적 없는 시각을 이어받는 원천이 된다.
 *
 * <p>api {@code WorkflowService}의 폴백 쪽(요청이 cron을 생략했을 때)은
 * {@code WorkflowPartialUpdateTest}가 본다. 여기는 요청이 cron을 직접 보낸 쪽이다.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowCrudServiceScheduleTest {

    private static final String CRON = "0 0 12 * * ?";
    private static final String NODES_JSON = "[]";
    private static final String EDGES_JSON = "[]";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowVersionRepository workflowVersionRepository;
    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Mock private WorkflowScheduler workflowScheduler;
    @Mock private WorkflowDefinitionRepository definitionRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private WorkflowCrudService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @Test
    @DisplayName("수정 요청이 cron을 보내도 유효 triggerType이 MANUAL이면 cron이 저장되지 않는다")
    void update_manualTrigger_dropsRequestedCron() {
        stubDefinitionSave();
        Workflow stored = workflow(TriggerType.MANUAL, null);
        given(workflowRepository.findByIdAndUserId(workflowId, userId))
            .willReturn(Optional.of(stored));

        // triggerType을 생략한 요청은 api 계층에서 저장된 값(MANUAL)으로 폴백돼 여기 도착한다.
        runInTransaction(() -> service.updateWorkflow(userId, workflowId, "이름", "설명",
            NODES_JSON, EDGES_JSON, TriggerType.MANUAL, CRON));

        assertThat(stored.getCronExpression()).isNull();
        assertThat(stored.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        verify(workflowScheduler, never()).registerJob(any(), any());
        verify(workflowScheduler).deleteJob(stored.getId());
    }

    @Test
    @DisplayName("생성 요청이 MANUAL + cron을 함께 보내도 cron이 저장되지 않는다")
    void create_manualTrigger_dropsRequestedCron() {
        stubDefinitionSave();

        runInTransaction(() -> service.createWorkflow(userId, "이름", "설명",
            NODES_JSON, EDGES_JSON, TriggerType.MANUAL, CRON));

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowRepository).save(saved.capture());
        assertThat(saved.getValue().getCronExpression()).isNull();
        verify(workflowScheduler, never()).registerJob(any(), any());
    }

    @Test
    @DisplayName("SCHEDULE + cron을 보내면 cron이 그대로 저장되고 Job이 등록된다")
    void update_scheduleTrigger_keepsRequestedCron() {
        stubDefinitionSave();
        Workflow stored = workflow(TriggerType.MANUAL, null);
        given(workflowRepository.findByIdAndUserId(workflowId, userId))
            .willReturn(Optional.of(stored));

        runInTransaction(() -> service.updateWorkflow(userId, workflowId, "이름", "설명",
            NODES_JSON, EDGES_JSON, TriggerType.SCHEDULE, CRON));

        assertThat(stored.getCronExpression()).isEqualTo(CRON);
        assertThat(stored.getTriggerType()).isEqualTo(TriggerType.SCHEDULE);
        verify(workflowScheduler).registerJob(stored.getId(), CRON);
        verify(workflowScheduler, never()).deleteJob(any());
    }

    @Test
    @DisplayName("REST 수정이 Unix 5필드 cron을 보내면 Quartz로 변환해 저장·등록한다 (IEUM-AI-56)")
    void update_unixCron_convertedToQuartz() {
        stubDefinitionSave();
        Workflow stored = workflow(TriggerType.MANUAL, null);
        given(workflowRepository.findByIdAndUserId(workflowId, userId))
            .willReturn(Optional.of(stored));

        runInTransaction(() -> service.updateWorkflow(userId, workflowId, "이름", "설명",
            NODES_JSON, EDGES_JSON, TriggerType.SCHEDULE, "0 9 * * *"));

        assertThat(stored.getCronExpression()).isEqualTo("0 0 9 * * ?");
        verify(workflowScheduler).registerJob(stored.getId(), "0 0 9 * * ?");
    }

    // ─────────────── AI 저장 경로 — TRIGGER 노드가 트리거 타입·cron의 진실원 (IEUM-AI-56) ───────────────

    @ParameterizedTest(name = "[{0}] → [{1}]")
    @CsvSource(delimiter = '|', value = {
        "0 9 * * *  | 0 0 9 * * ?",
        "0 17 * * 5 | 0 0 17 ? * 6",
    })
    @DisplayName("AI 저장이 SCHEDULE + Unix cron을 실으면 Quartz로 변환해 저장하고 커밋 후 Job을 등록한다")
    void saveAgentVersion_scheduleTrigger_registersQuartzCron(String unixCron, String quartzCron) {
        stubDefinitionSave();
        Workflow stored = workflow(TriggerType.MANUAL, null);
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(stored));

        runInTransaction(() -> service.saveAgentVersion(workflowId, NODES_JSON, EDGES_JSON,
            TriggerType.SCHEDULE, unixCron));

        assertThat(stored.getTriggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(stored.getCronExpression()).isEqualTo(quartzCron);
        verify(workflowScheduler).registerJob(stored.getId(), quartzCron);
        verify(workflowScheduler, never()).deleteJob(any());
    }

    @ParameterizedTest
    @EnumSource(value = TriggerType.class, names = {"MANUAL", "WEBHOOK"})
    @DisplayName("AI 저장이 SCHEDULE 아닌 트리거를 실으면 트리거 타입이 바뀌고 기존 스케줄이 해제된다")
    void saveAgentVersion_nonScheduleTrigger_unschedules(TriggerType triggerType) {
        stubDefinitionSave();
        Workflow stored = workflow(TriggerType.SCHEDULE, CRON);
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(stored));

        runInTransaction(() -> service.saveAgentVersion(workflowId, NODES_JSON, EDGES_JSON,
            triggerType, null));

        assertThat(stored.getTriggerType()).isEqualTo(triggerType);
        assertThat(stored.getCronExpression()).isNull();
        verify(workflowScheduler).deleteJob(stored.getId());
        verify(workflowScheduler, never()).registerJob(any(), any());
    }

    @Test
    @DisplayName("AI 저장에 트리거 정보가 없으면 트리거 타입·cron·Job을 건드리지 않는다")
    void saveAgentVersion_noTrigger_leavesScheduleUntouched() {
        stubDefinitionSave();
        Workflow stored = workflow(TriggerType.SCHEDULE, CRON);
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(stored));

        runInTransaction(() -> service.saveAgentVersion(workflowId, NODES_JSON, EDGES_JSON, null, null));

        assertThat(stored.getTriggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(stored.getCronExpression()).isEqualTo(CRON);
        verifyNoInteractions(workflowScheduler);
    }

    @Test
    @DisplayName("AI 저장의 cron을 Quartz로 옮길 수 없으면 버전을 저장하기 전에 거부한다")
    void saveAgentVersion_unconvertibleCron_rejectedBeforeSave() {
        Workflow stored = workflow(TriggerType.MANUAL, null);
        given(workflowRepository.findById(workflowId)).willReturn(Optional.of(stored));

        // 일·요일 동시 지정 — Unix는 OR로 실행하지만 Quartz는 표현하지 못한다.
        assertThatThrownBy(() -> runInTransaction(() -> service.saveAgentVersion(
                workflowId, NODES_JSON, EDGES_JSON, TriggerType.SCHEDULE, "0 9 1 * 1")))
            .isInstanceOfSatisfying(CustomException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION));

        verify(definitionRepository, never()).save(any());
        verifyNoInteractions(workflowScheduler);
    }

    private Workflow workflow(TriggerType triggerType, String cronExpression) {
        return Workflow.builder()
            .userId(userId)
            .name("이름")
            .isActive(true)
            .triggerType(triggerType)
            .cronExpression(cronExpression)
            .build();
    }

    private void stubDefinitionSave() {
        given(definitionRepository.save(any(WorkflowDefinitionDocument.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    /** Quartz 조작은 afterCommit에 걸려 있어 커밋 시뮬레이션까지 해야 관찰된다. */
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
