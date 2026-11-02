package com.ieum.api.workflow.queue;

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
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Validate the actual JPQL boundaries/projection/limit against the test database. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
@Transactional
class PendingRecoveryObservationRepositoryTest {
    @Autowired private WorkflowExecutionRepository executions;
    @Autowired private WorkflowRepository workflows;
    @Autowired private WorkflowVersionRepository versions;

    @Test
    void observationSeparatesHistoricalAnd61MinutePendingWithoutChangingScheduleBlockingPolicy() {
        var workflow = workflow();
        var version = versions.save(WorkflowVersion.builder().workflow(workflow).version(1)
            .mongoDefinitionId("0123456789abcdef01234567").build());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        var historical = execution(workflow, version, now.minusHours(3), ExecutionStatus.PENDING);
        var aged = execution(workflow, version, now.minusMinutes(61), ExecutionStatus.PENDING);
        var recent = execution(workflow, version, now.minusMinutes(30), ExecutionStatus.PENDING);
        execution(workflow, version, now.minusHours(3), ExecutionStatus.RUNNING);
        execution(workflow, version, now.minusHours(3), ExecutionStatus.SUCCESS);
        executions.flush();
        var cutoff = now.minusHours(2);
        var ageLimit = now.minusHours(1);

        assertThat(executions.findPendingObservationsBefore(cutoff, PageRequest.of(0, 101)))
            .extracting(WorkflowExecutionRepository.PendingObservation::getId).containsExactly(historical.getId());
        var observations = executions.findPendingObservationsBetween(cutoff, ageLimit, PageRequest.of(0, 101));
        assertThat(observations).extracting(WorkflowExecutionRepository.PendingObservation::getId)
            .containsExactly(aged.getId());
        assertThat(observations.getFirst().getStartedAt()).isEqualTo(aged.getStartedAt());
        assertThat(executions.findPendingIdsBetween(ageLimit, now.minusMinutes(1), PageRequest.of(0, 100)))
            .containsExactly(recent.getId());
        // Remove the in-window fixture from the guard so only the 61-minute PENDING can block it.
        assertThat(executions.finishIfNotTerminal(recent.getId(), ExecutionStatus.SUCCESS, false, now)).isEqualTo(1);
        assertThat(executions.existsByWorkflowAndTriggerTypeAndStatusInAndStartedAtAfter(workflow,
            TriggerType.SCHEDULE, java.util.List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING), cutoff))
            .isTrue();
        assertThat(executions.findById(aged.getId()).orElseThrow().getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void observationQueriesHonorThePageLimitAndOldestFirstOrder() {
        var workflow = workflow();
        var version = versions.save(WorkflowVersion.builder().workflow(workflow).version(1)
            .mongoDefinitionId("1123456789abcdef01234567").build());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        var oldest = execution(workflow, version, now.minusHours(4), ExecutionStatus.PENDING);
        execution(workflow, version, now.minusHours(2), ExecutionStatus.PENDING);
        executions.flush();
        assertThat(executions.findPendingObservationsBefore(now, PageRequest.of(0, 1)))
            .extracting(WorkflowExecutionRepository.PendingObservation::getId).containsExactly(oldest.getId());
    }

    private Workflow workflow() {
        return workflows.save(Workflow.builder().userId(UUID.randomUUID()).name("pending observation fixture")
            .isActive(true).triggerType(TriggerType.SCHEDULE).build());
    }

    private WorkflowExecution execution(Workflow workflow, WorkflowVersion version, LocalDateTime started,
            ExecutionStatus status) {
        return executions.save(WorkflowExecution.builder().workflow(workflow).workflowVersion(version)
            .startedAt(started).status(status).triggerType(TriggerType.SCHEDULE).build());
    }
}
