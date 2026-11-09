package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository.DueDelay;
import com.ieum.workflowcore.service.DelayQueueService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DelayResumeSweeperTest {

    private final WorkflowExecutionService workflowExecutionService = mock(WorkflowExecutionService.class);
    private final ExecutionDelayService executionDelayService = mock(ExecutionDelayService.class);
    private final DelayQueueService delayQueueService = mock(DelayQueueService.class);
    private final DelayResumeSweeper sweeper =
        new DelayResumeSweeper(workflowExecutionService, executionDelayService, delayQueueService);

    private static DueDelay due(UUID id, LocalDateTime resumeAt) {
        return new DueDelay() {
            @Override public UUID getId() { return id; }
            @Override public LocalDateTime getResumeAt() { return resumeAt; }
        };
    }

    private static List<DueDelay> page(int size, LocalDateTime resumeAt) {
        List<DueDelay> list = new ArrayList<>();
        for (int i = 0; i < size; i++) list.add(due(UUID.randomUUID(), resumeAt));
        return list;
    }

    private void owner(Boolean... answers) {
        BackgroundOwnership ownership = mock(BackgroundOwnership.class);
        given(ownership.isOwner()).willReturn(answers[0], java.util.Arrays.copyOfRange(answers, 1, answers.length));
        ReflectionTestUtils.setField(sweeper, "backgroundOwnership", ownership);
    }

    @Test
    @DisplayName("소유자가 아니면 조회도 안 한다")
    void 소유자가_아니면_조회도_안한다() {
        owner(false);

        sweeper.sweep();

        verify(workflowExecutionService, never()).findDueDelayPage(any(), any(), any(), anyInt());
        verify(delayQueueService, never()).purgeOldSlots(any());
    }

    @Test
    @DisplayName("한 건 실패해도 나머지는 재개")
    void 한_건_실패해도_나머지는_재개() {
        LocalDateTime recent = LocalDateTime.now().minusMinutes(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), anyInt()))
            .willReturn(List.of(due(first, recent), due(second, recent)));
        given(executionDelayService.resume(eq(first), any())).willThrow(new IllegalStateException("boom"));

        sweeper.sweep();

        verify(executionDelayService).resume(eq(second), any());
    }

    @Test
    @DisplayName("페이지(100건)가 꽉 차면 마지막 행 다음부터 이어서 읽는다")
    void fullPage_continuesFromLastRow() {
        LocalDateTime at = LocalDateTime.now().minusMinutes(5);
        List<DueDelay> full = page(100, at);
        DueDelay tail = due(UUID.randomUUID(), at);
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), eq(100)))
            .willReturn(full, List.of(tail));

        sweeper.sweep();

        verify(executionDelayService, times(101)).resume(any(), any());
        ArgumentCaptor<LocalDateTime> afterAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<UUID> afterId = ArgumentCaptor.forClass(UUID.class);
        verify(workflowExecutionService, times(2))
            .findDueDelayPage(any(), afterAt.capture(), afterId.capture(), eq(100));
        assertThat(afterAt.getAllValues().get(1)).isEqualTo(at);
        assertThat(afterId.getAllValues().get(1)).isEqualTo(full.get(99).getId());
    }

    @Test
    @DisplayName("페이지마다 소유권을 다시 본다 — 중간에 잃으면 다음 페이지를 읽지 않는다")
    void ownershipRecheckedPerPage() {
        owner(true, false);
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), eq(100)))
            .willReturn(page(100, LocalDateTime.now().minusMinutes(5)));

        sweeper.sweep();

        verify(workflowExecutionService, times(1)).findDueDelayPage(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("CustomException으로 재개가 실패하면 WAITING_DELAY를 '재개하지 못했어요'로 닫는다")
    void customException_closesAsFailed() {
        UUID id = UUID.randomUUID();
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), anyInt()))
            .willReturn(List.of(due(id, LocalDateTime.now().minusMinutes(1))));
        given(executionDelayService.resume(eq(id), any()))
            .willThrow(new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED));

        sweeper.sweep();

        verify(workflowExecutionService).markAsFailed(id, "재개하지 못했어요", ExecutionStatus.WAITING_DELAY);
    }

    @Test
    @DisplayName("일시 예외라도 재개 시각이 1일 넘게 지났으면 닫는다")
    void transientFailure_overOneDay_closesAsFailed() {
        UUID id = UUID.randomUUID();
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), anyInt()))
            .willReturn(List.of(due(id, LocalDateTime.now().minusDays(1).minusMinutes(1))));
        given(executionDelayService.resume(eq(id), any())).willThrow(new IllegalStateException("db hiccup"));

        sweeper.sweep();

        verify(workflowExecutionService).markAsFailed(id, "재개하지 못했어요", ExecutionStatus.WAITING_DELAY);
    }

    @Test
    @DisplayName("일시 예외이고 1일 안이면 닫지 않고 다음 스윕에 다시 본다")
    void transientFailure_withinOneDay_retriesLater() {
        UUID id = UUID.randomUUID();
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), anyInt()))
            .willReturn(List.of(due(id, LocalDateTime.now().minusMinutes(5))));
        given(executionDelayService.resume(eq(id), any())).willThrow(new IllegalStateException("db hiccup"));

        sweeper.sweep();

        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
    }

    @Test
    @DisplayName("스윕 끝에 오래된 큐 슬롯을 정리한다")
    void purgesOldSlots() {
        given(workflowExecutionService.findDueDelayPage(any(), any(), any(), anyInt())).willReturn(List.of());

        sweeper.sweep();

        verify(delayQueueService).purgeOldSlots(any());
    }
}
