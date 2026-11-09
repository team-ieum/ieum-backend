package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * 게이트 테이블. 클래스 트랜잭션을 걸지 않는다 — {@code markNotified}·{@code markReminded}가 트랜잭션 밖에서
 * 도는지 봐야 한다(sweeper가 그렇게 부른다). 매 테스트 뒤 만든 행만 지운다.
 */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class ApprovalGateRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Autowired private ApprovalGateRepository gates;
    @Autowired private WorkflowExecutionRepository executions;
    @Autowired private WorkflowRepository workflows;
    @Autowired private WorkflowVersionRepository versions;

    private final UUID u = UUID.randomUUID();
    private Workflow workflow;
    private WorkflowVersion version;
    private final List<UUID> createdRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workflow = workflows.save(Workflow.builder().userId(u).name("approval gate fixture")
            .isActive(true).triggerType(TriggerType.MANUAL).build());
        version = versions.save(WorkflowVersion.builder().workflow(workflow).version(1)
            .mongoDefinitionId("3123456789abcdef01234568").build());
    }

    @AfterEach
    void clean() {
        gates.deleteByUserId(u);
        executions.deleteAllById(createdRuns);
        versions.delete(version);
        workflows.delete(workflow);
    }

    private UUID run(ExecutionStatus status) {
        WorkflowExecution e = executions.save(WorkflowExecution.builder().workflow(workflow).workflowVersion(version)
            .startedAt(NOW).status(status).triggerType(TriggerType.MANUAL).build());
        createdRuns.add(e.getId());
        return e.getId();
    }

    private ApprovalGate gate(UUID executionId, String nodeId, UUID webhookId, LocalDateTime remindAt) {
        return ApprovalGate.open(executionId, workflow.getId(), u, nodeId, "approval.request", "{}",
            webhookId, NOW.plusHours(24), remindAt);
    }

    @Test
    @DisplayName("같은_실행_같은_노드는_한_번만")
    void sameExecutionSameNode_onlyOnce() {
        UUID e = run(ExecutionStatus.WAITING_APPROVAL);
        gates.saveAndFlush(gate(e, "n1", null, null));

        assertThatThrownBy(() -> gates.saveAndFlush(gate(e, "n1", null, null)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("열린_게이트는_대기_실행의_미결정만")
    void openGates_onlyUndecidedOfWaitingExecution() {
        ApprovalGate open = gates.save(gate(run(ExecutionStatus.WAITING_APPROVAL), "n1", null, null));
        ApprovalGate decided = gate(run(ExecutionStatus.WAITING_APPROVAL), "n1", null, null);
        decided.decide("approved", "{}", NOW);
        gates.save(decided);
        gates.save(gate(run(ExecutionStatus.FAILED), "n1", null, null));

        List<ApprovalGate> found = gates.findOpenByUser(u, PageRequest.of(0, 10));

        assertThat(found).extracting(ApprovalGate::getId).containsExactly(open.getId());
    }

    @Test
    @DisplayName("알림_선점은_한_번만")
    void notifyClaim_onlyOnce() {
        ApprovalGate g = gates.save(gate(run(ExecutionStatus.WAITING_APPROVAL), "n1", UUID.randomUUID(), null));

        assertThat(gates.markNotified(g.getId(), NOW)).isEqualTo(1);
        assertThat(gates.markNotified(g.getId(), NOW)).isEqualTo(0);
        assertThat(gates.markReminded(g.getId(), NOW)).isEqualTo(1);
        assertThat(gates.markReminded(g.getId(), NOW)).isEqualTo(0);
    }

    @Test
    @DisplayName("선점_되돌리기는_그_시각으로_선점한_행만")
    void unmark_onlyOwnClaim() {
        ApprovalGate g = gates.save(gate(run(ExecutionStatus.WAITING_APPROVAL), "n1", UUID.randomUUID(), null));
        gates.markNotified(g.getId(), NOW);
        gates.markReminded(g.getId(), NOW);

        assertThat(gates.unmarkNotified(g.getId(), NOW.minusSeconds(1))).isZero();
        assertThat(gates.unmarkReminded(g.getId(), NOW.minusSeconds(1))).isZero();
        assertThat(gates.unmarkNotified(g.getId(), NOW)).isEqualTo(1);
        assertThat(gates.unmarkReminded(g.getId(), NOW)).isEqualTo(1);

        assertThat(gates.markNotified(g.getId(), NOW)).isEqualTo(1);
        assertThat(gates.markReminded(g.getId(), NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("리마인더_대상은_시각이_지난_미발송만")
    void dueReminders_onlyPastUnsent() {
        UUID e = run(ExecutionStatus.WAITING_APPROVAL);
        UUID webhook = UUID.randomUUID();
        ApprovalGate past = gates.save(gate(e, "past", webhook, NOW.minusMinutes(1)));
        gates.save(gate(e, "future", webhook, NOW.plusMinutes(1)));
        gates.save(gate(e, "none", webhook, null));

        List<ApprovalGate> due = gates.findDueReminders(NOW, PageRequest.of(0, 10));

        assertThat(due).extracting(ApprovalGate::getId).containsExactly(past.getId());
    }
}
