package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** 기다리기(WAITING_DELAY) 실행에 대한 조건부 UPDATE JPQL을 실제 테스트 DB로 확인한다. 테스트마다 롤백으로 비운다. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
@Transactional
class DelayExecutionRepositoryTest {
    @Autowired private WorkflowExecutionRepository executions;
    @Autowired private WorkflowRepository workflows;
    @Autowired private WorkflowVersionRepository versions;
    @Autowired private EntityManager entityManager;

    @Test
    void 기다리는_실행은_종료_시작_사유기록이_덮지_않는다() {
        var execution = execution(ExecutionStatus.WAITING_DELAY);
        UUID id = execution.getId();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        assertThat(executions.finishIfNotTerminal(id, ExecutionStatus.SUCCESS, false, now)).isZero();
        assertThat(executions.startIfNotTerminal(id, now)).isZero();
        assertThat(executions.recordErrorIfNotTerminal(id, "x", now)).isZero();

        entityManager.clear();
        var reloaded = executions.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.WAITING_DELAY);
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    private static final String NODES_JSON = "{\"d\":{\"scheduledFor\":\"2026-11-09T10:40:00+09:00\"}}";

    @Test
    void pauseForDelay_RUNNING만() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime resumeAt = now.plusMinutes(10);
        var running = execution(ExecutionStatus.RUNNING);
        var pending = execution(ExecutionStatus.PENDING);

        assertThat(executions.pauseForDelay(running.getId(), resumeAt, NODES_JSON, now)).isEqualTo(1);
        assertThat(executions.pauseForDelay(pending.getId(), resumeAt, NODES_JSON, now)).isZero();

        entityManager.clear();
        var paused = executions.findById(running.getId()).orElseThrow();
        assertThat(paused.getStatus()).isEqualTo(ExecutionStatus.WAITING_DELAY);
        assertThat(paused.getResumeAt()).isEqualTo(resumeAt);
        assertThat(paused.getWaitingDelayNodes()).isEqualTo(NODES_JSON);
        var untouched = executions.findById(pending.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(untouched.getResumeAt()).isNull();
        assertThat(untouched.getWaitingDelayNodes()).isNull();
    }

    @Test
    void recordDelayWait_상태는_그대로() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime resumeAt = now.plusMinutes(10);
        var running = execution(ExecutionStatus.RUNNING);
        var waitingApproval = execution(ExecutionStatus.WAITING_APPROVAL);

        assertThat(executions.recordDelayWait(running.getId(), resumeAt, NODES_JSON, now)).isEqualTo(1);
        assertThat(executions.recordDelayWait(waitingApproval.getId(), resumeAt, NODES_JSON, now)).isZero();

        entityManager.clear();
        var recorded = executions.findById(running.getId()).orElseThrow();
        assertThat(recorded.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(recorded.getResumeAt()).isEqualTo(resumeAt);
        assertThat(recorded.getWaitingDelayNodes()).isEqualTo(NODES_JSON);
        assertThat(executions.findById(waitingApproval.getId()).orElseThrow().getResumeAt()).isNull();
    }

    @Test
    void findDueDelayIds() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        var due = execution(ExecutionStatus.WAITING_DELAY, now.minusMinutes(1));
        execution(ExecutionStatus.WAITING_DELAY, now.plusMinutes(1));
        execution(ExecutionStatus.FAILED, now.minusMinutes(1));
        entityManager.clear();

        assertThat(executions.findDueDelayIds(now)).containsExactly(due.getId());
    }

    private WorkflowExecution execution(ExecutionStatus status) {
        return execution(status, null);
    }

    private WorkflowExecution execution(ExecutionStatus status, LocalDateTime resumeAt) {
        var workflow = workflows.save(Workflow.builder().userId(UUID.randomUUID()).name("delay fixture")
            .isActive(true).triggerType(TriggerType.MANUAL).build());
        var version = versions.save(WorkflowVersion.builder().workflow(workflow).version(1)
            .mongoDefinitionId("2123456789abcdef01234567").build());
        var built = WorkflowExecution.builder().workflow(workflow).workflowVersion(version)
            .startedAt(LocalDateTime.now().withNano(0)).status(status).triggerType(TriggerType.MANUAL).build();
        ReflectionTestUtils.setField(built, "resumeAt", resumeAt);
        var saved = executions.save(built);
        executions.flush();
        return saved;
    }
}
