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

    private WorkflowExecution execution(ExecutionStatus status) {
        var workflow = workflows.save(Workflow.builder().userId(UUID.randomUUID()).name("delay fixture")
            .isActive(true).triggerType(TriggerType.MANUAL).build());
        var version = versions.save(WorkflowVersion.builder().workflow(workflow).version(1)
            .mongoDefinitionId("2123456789abcdef01234567").build());
        var saved = executions.save(WorkflowExecution.builder().workflow(workflow).workflowVersion(version)
            .startedAt(LocalDateTime.now().withNano(0)).status(status).triggerType(TriggerType.MANUAL).build());
        executions.flush();
        return saved;
    }
}
