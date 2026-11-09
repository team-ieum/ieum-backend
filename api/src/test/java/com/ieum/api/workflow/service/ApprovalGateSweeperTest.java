package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ieum.api.alert.ApprovalNotifier;
import com.ieum.api.alert.DiscordWebhookSender.SendResult;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.executor.ApprovalFailure;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository.ExpiredApproval;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ApprovalGateSweeperTest {

    private final WorkflowExecutionService workflowExecutionService = mock(WorkflowExecutionService.class);
    private final ApprovalGateService approvalGateService = mock(ApprovalGateService.class);
    private final ApprovalGateRepository approvalGateRepository = mock(ApprovalGateRepository.class);
    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final ApprovalNotifier approvalNotifier = mock(ApprovalNotifier.class);
    private final ApprovalGateSweeper sweeper = new ApprovalGateSweeper(workflowExecutionService,
        approvalGateService, approvalGateRepository, workflowCrudService, approvalNotifier);

    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(workflowExecutionService.findExpiredApprovals(any())).willReturn(List.of());
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of());
        given(approvalGateRepository.findDueReminders(any(), any())).willReturn(List.of());
        given(workflowCrudService.getWorkflowById(workflowId))
            .willReturn(Workflow.builder().userId(UUID.randomUUID()).name("주간 보고").isActive(true).build());
    }

    private ApprovalGate gate() {
        return gate(UUID.randomUUID());
    }

    private ApprovalGate gate(UUID webhookId) {
        ApprovalGate g = ApprovalGate.open(UUID.randomUUID(), workflowId, UUID.randomUUID(), "g",
            "approval.request", "{}", "", webhookId, LocalDateTime.now().plusHours(1), null);
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        return g;
    }

    private static ExpiredApproval expired(UUID id, LocalDateTime deadline) {
        return new ExpiredApproval() {
            @Override public UUID getId() { return id; }
            @Override public LocalDateTime getApprovalDeadline() { return deadline; }
        };
    }

    private static ExpiredApproval recent(UUID id) {
        return expired(id, LocalDateTime.now().minusMinutes(1));
    }

    private List<ApprovalGate> gates(int n) {
        List<ApprovalGate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(gate());
        return list;
    }

    private void owner(Boolean... answers) {
        BackgroundOwnership ownership = mock(BackgroundOwnership.class);
        given(ownership.isOwner()).willReturn(answers[0], java.util.Arrays.copyOfRange(answers, 1, answers.length));
        ReflectionTestUtils.setField(sweeper, "backgroundOwnership", ownership);
    }

    @Test
    @DisplayName("선점(markNotified)이 1일 때만 알림을 보낸다 — 다른 인스턴스가 먼저 가져가면 안 보낸다")
    void 알림은_선점한_한_번만_보낸다() {
        ApprovalGate taken = gate();
        ApprovalGate mine = gate();
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of(taken, mine));
        given(approvalGateRepository.markNotified(eq(taken.getId()), any())).willReturn(0);
        given(approvalGateRepository.markNotified(eq(mine.getId()), any())).willReturn(1);

        sweeper.sweep();

        verify(approvalNotifier, never()).notifyRequested(eq(taken), any());
        verify(approvalNotifier).notifyRequested(mine, "주간 보고");
    }

    @Test
    @DisplayName("리마인더도 선점(markReminded)한 것만 보낸다")
    void 리마인더도_선점한_것만() {
        ApprovalGate g = gate();
        given(approvalGateRepository.findDueReminders(any(), any())).willReturn(List.of(g));
        given(approvalGateRepository.markReminded(eq(g.getId()), any())).willReturn(1);

        sweeper.sweep();

        verify(approvalNotifier).notifyReminder(g, "주간 보고");
    }

    @Test
    @DisplayName("소유자가 아니면 만료·알림 조회를 하지 않는다")
    void 소유자가_아니면_아무것도_안_한다() {
        owner(false);

        sweeper.sweep();

        verify(workflowExecutionService, never()).findExpiredApprovals(any());
        verify(approvalGateRepository, never()).findUnnotified(any(), any());
        verify(approvalGateRepository, never()).findDueReminders(any(), any());
        verify(approvalGateService, never()).expire(any(), any());
    }

    @Test
    @DisplayName("만료 한 건·알림 한 건이 실패해도 나머지는 처리한다")
    void 한_건_실패가_나머지를_막지_않는다() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(workflowExecutionService.findExpiredApprovals(any())).willReturn(List.of(recent(first), recent(second)));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("boom"))
            .given(approvalGateService).expire(eq(first), any());
        ApprovalGate bad = gate();
        ApprovalGate good = gate();
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of(bad, good));
        given(approvalGateRepository.markNotified(eq(bad.getId()), any())).willThrow(new IllegalStateException("x"));
        given(approvalGateRepository.markNotified(eq(good.getId()), any())).willReturn(1);

        sweeper.sweep();

        verify(approvalGateService).expire(eq(second), any());
        verify(approvalNotifier).notifyRequested(good, "주간 보고");
    }

    @Test
    @DisplayName("만료가 영구 오류(CustomException)면 닫고, DB 일시 오류면 닫지 않는다")
    void 영구_오류만_닫는다() {
        UUID permanent = UUID.randomUUID();
        UUID transientId = UUID.randomUUID();
        given(workflowExecutionService.findExpiredApprovals(any()))
            .willReturn(List.of(recent(permanent), recent(transientId)));
        org.mockito.BDDMockito.willThrow(new CustomException(ErrorCode.INVALID_WORKFLOW))
            .given(approvalGateService).expire(eq(permanent), any());
        org.mockito.BDDMockito.willThrow(new DataAccessResourceFailureException("db"))
            .given(approvalGateService).expire(eq(transientId), any());

        sweeper.sweep();

        verify(workflowExecutionService).markAsFailed(
            eq(permanent), eq(ApprovalGateSweeper.EXPIRE_FAILED), eq(ExecutionStatus.WAITING_APPROVAL));
        verify(workflowExecutionService, never()).markAsFailed(eq(transientId), any(), any());
    }

    @Test
    @DisplayName("CustomException이 아닌 영구 오류(ApprovalFailure)는 기한이 1일 넘게 지났을 때 닫고, 그 전엔 닫지 않는다")
    void 만료_영구오류는_하루_지나면_닫는다() {
        UUID overdue = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        given(workflowExecutionService.findExpiredApprovals(any()))
            .willReturn(List.of(expired(overdue, LocalDateTime.now().minusDays(2)), recent(fresh)));
        org.mockito.BDDMockito.willThrow(new ApprovalFailure("제한 시간은 5분~7일이에요"))
            .given(approvalGateService).expire(any(), any());

        sweeper.sweep();

        verify(workflowExecutionService).markAsFailed(
            eq(overdue), eq(ApprovalGateSweeper.EXPIRE_FAILED), eq(ExecutionStatus.WAITING_APPROVAL));
        verify(workflowExecutionService, never()).markAsFailed(eq(fresh), any(), any());
    }

    @Test
    @DisplayName("DB 일시 오류는 기한이 1일 넘게 지났어도 닫지 않는다")
    void 만료_DB_일시오류는_닫지_않는다() {
        UUID id = UUID.randomUUID();
        given(workflowExecutionService.findExpiredApprovals(any()))
            .willReturn(List.of(expired(id, LocalDateTime.now().minusDays(2))));
        org.mockito.BDDMockito.willThrow(new DataAccessResourceFailureException("db"))
            .given(approvalGateService).expire(eq(id), any());

        sweeper.sweep();

        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
    }

    @Test
    @DisplayName("선점하면 결과에서 빠지므로 항상 0번 페이지를 다시 읽고, 페이지가 덜 차면 멈춘다")
    void 항상_0번_페이지를_읽는다() {
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(gates(100), gates(3));
        given(approvalGateRepository.markNotified(any(), any())).willReturn(1);

        sweeper.sweep();

        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(approvalGateRepository, times(2)).findUnnotified(any(), pages.capture());
        assertThat(pages.getAllValues()).allSatisfy(p -> {
            assertThat(p.getPageNumber()).isZero();
            assertThat(p.getPageSize()).isEqualTo(ApprovalGateSweeper.PAGE_SIZE);
        });
        verify(approvalNotifier, times(103)).notifyRequested(any(), any());
    }

    @Test
    @DisplayName("꽉 찬 페이지에서 하나도 선점하지 못하면 멈춘다 — 무한 루프 없음")
    void 선점이_없으면_멈춘다() {
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(gates(100));
        given(approvalGateRepository.markNotified(any(), any())).willReturn(0);

        sweeper.sweep();

        verify(approvalGateRepository, times(1)).findUnnotified(any(), any());
        verify(approvalNotifier, never()).notifyRequested(any(), any());
    }

    @Test
    @DisplayName("일시 실패(RETRYABLE)면 선점 시각으로 선점을 되돌려 다음 스윕에 다시 보낸다")
    void 일시_실패는_다음_스윕에_다시_보낸다() {
        ApprovalGate g = gate();
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of(g));
        given(approvalGateRepository.markNotified(eq(g.getId()), any())).willReturn(1);
        given(approvalNotifier.notifyRequested(eq(g), any())).willReturn(SendResult.RETRYABLE, SendResult.SENT);

        sweeper.sweep();

        ArgumentCaptor<LocalDateTime> claimedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(approvalGateRepository).markNotified(eq(g.getId()), claimedAt.capture());
        verify(approvalGateRepository).unmarkNotified(g.getId(), claimedAt.getValue());

        sweeper.sweep();

        verify(approvalNotifier, times(2)).notifyRequested(eq(g), any());
        verify(approvalGateRepository, times(1)).unmarkNotified(any(), any());
    }

    @Test
    @DisplayName("같은 웹훅이 429면 그 스윕에선 그 웹훅으로 더 선점·발송하지 않는다 — 다른 웹훅은 보낸다")
    void 같은_웹훅이_429면_그_스윕에서_그만_보낸다() {
        UUID limited = UUID.randomUUID();
        ApprovalGate first = gate(limited);
        ApprovalGate second = gate(limited);
        ApprovalGate other = gate(UUID.randomUUID());
        ApprovalGate reminder = gate(limited);
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of(first, second, other));
        given(approvalGateRepository.findDueReminders(any(), any())).willReturn(List.of(reminder));
        given(approvalGateRepository.markNotified(any(), any())).willReturn(1);
        given(approvalGateRepository.markReminded(any(), any())).willReturn(1);
        given(approvalNotifier.notifyRequested(eq(first), any())).willReturn(SendResult.RETRYABLE);
        given(approvalNotifier.notifyRequested(eq(other), any())).willReturn(SendResult.SENT);

        sweeper.sweep();

        verify(approvalGateRepository, never()).markNotified(eq(second.getId()), any());
        verify(approvalNotifier, never()).notifyRequested(eq(second), any());
        verify(approvalNotifier).notifyRequested(eq(other), any());
        verify(approvalGateRepository, never()).markReminded(eq(reminder.getId()), any());
        verify(approvalGateRepository, times(1)).unmarkNotified(any(), any());
    }

    @Test
    @DisplayName("영구 실패(PERMANENT)는 선점을 되돌리지 않는다 — 다시 보내지 않는다")
    void 영구_실패는_다시_보내지_않는다() {
        ApprovalGate g = gate();
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of(g));
        given(approvalGateRepository.markNotified(eq(g.getId()), any())).willReturn(1);
        given(approvalNotifier.notifyRequested(eq(g), any())).willReturn(SendResult.PERMANENT);

        sweeper.sweep();

        verify(approvalGateRepository, never()).unmarkNotified(any(), any());
    }

    @Test
    @DisplayName("되돌린 행은 같은 스윕에서 다시 선점하지 않는다 — 꽉 찬 페이지가 전부 되돌려져도 멈춘다")
    void 되돌린_행은_같은_스윕에서_다시_선점하지_않는다() {
        List<ApprovalGate> full = gates(100);
        byOffset(full);
        given(approvalGateRepository.markNotified(any(), any())).willReturn(1);
        given(approvalNotifier.notifyRequested(any(), any())).willReturn(SendResult.RETRYABLE);

        sweeper.sweep();

        verify(approvalGateRepository, times(2)).findUnnotified(any(), any());
        verify(approvalGateRepository, times(100)).markNotified(any(), any());
        verify(approvalGateRepository, times(100)).unmarkNotified(any(), any());
    }

    /** 실제 DB처럼 offset 기준으로 남은 행을 돌려준다(선점 뒤 결과에서 빠지는 것은 각 테스트가 정한다). */
    private void byOffset(List<ApprovalGate> rows) {
        given(approvalGateRepository.findUnnotified(any(), any())).willAnswer(inv -> {
            Pageable p = inv.getArgument(1);
            int from = (int) Math.min(p.getOffset(), rows.size());
            return rows.subList(from, Math.min(rows.size(), from + p.getPageSize()));
        });
    }

    @Test
    @DisplayName("앞 100건이 모두 일시 실패(RETRYABLE) 웹훅이어도 101번째 게이트 알림이 그 스윕에서 나간다 — 같은 행은 한 번만")
    void 건너뛴_행으로_꽉_찬_페이지_뒤도_보낸다() {
        List<ApprovalGate> rows = gates(101);
        ApprovalGate last = rows.get(100);
        byOffset(rows);
        given(approvalGateRepository.markNotified(any(), any())).willReturn(1);
        given(approvalNotifier.notifyRequested(any(), any())).willReturn(SendResult.RETRYABLE);
        given(approvalNotifier.notifyRequested(eq(last), any())).willReturn(SendResult.SENT);

        sweeper.sweep();

        verify(approvalNotifier).notifyRequested(last, "주간 보고");
        verify(approvalGateRepository, times(101)).markNotified(any(), any());
        verify(approvalNotifier, times(101)).notifyRequested(any(), any());
    }

    @Test
    @DisplayName("연결 단계 실패(UNREACHABLE)면 선점을 되돌리고 이 스윕의 남은 알림·리마인더를 멈춘다")
    void 연결_실패면_남은_발송을_멈춘다() {
        ApprovalGate first = gate();
        ApprovalGate second = gate();
        ApprovalGate reminder = gate();
        given(approvalGateRepository.findUnnotified(any(), any())).willReturn(List.of(first, second));
        given(approvalGateRepository.findDueReminders(any(), any())).willReturn(List.of(reminder));
        given(approvalGateRepository.markNotified(any(), any())).willReturn(1);
        given(approvalGateRepository.markReminded(any(), any())).willReturn(1);
        given(approvalNotifier.notifyRequested(eq(first), any())).willReturn(SendResult.UNREACHABLE);

        sweeper.sweep();

        verify(approvalGateRepository).unmarkNotified(eq(first.getId()), any());
        verify(approvalGateRepository, never()).markNotified(eq(second.getId()), any());
        verify(approvalNotifier, never()).notifyRequested(eq(second), any());
        verify(approvalGateRepository, never()).findDueReminders(any(), any());
        verify(approvalNotifier, never()).notifyReminder(any(), any());
    }
}
