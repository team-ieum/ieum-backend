package com.ieum.workflowcore.scheduler;

import com.ieum.workflowcore.config.StuckExecutionProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCleanupScheduler 단위 테스트")
class WorkflowCleanupSchedulerTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private ExecutionEventPublisher executionEventPublisher;

    // 임계값은 실제 기본값(2시간)을 그대로 쓴다 — 스텁으로 덮으면 기본값 회귀를 잡지 못한다.
    @Spy private StuckExecutionProperties stuckExecutionProperties = new StuckExecutionProperties();

    @InjectMocks
    private WorkflowCleanupScheduler scheduler;

    // ─────────────────── 삭제 대상 ──────────────────────────────────────────

    @Test
    @DisplayName("생성 후 30분 초과 + maxVersion==1 + nodes 비어있는 워크플로우는 삭제된다")
    void cleanupOrphanWorkflows_emptyNodes_deleted() {
        UUID orphanId = UUID.randomUUID();
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

        given(workflowCrudService.findOrphanCandidates(any())).willReturn(List.of(orphanId));
        given(workflowCrudService.findLatestVersion(orphanId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(buildDoc(List.of()));

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService).deleteWorkflowById(orphanId);
    }

    @Test
    @DisplayName("nodes가 null인 경우도 빈 워크플로우로 간주하여 삭제한다")
    void cleanupOrphanWorkflows_nullNodes_deleted() {
        UUID orphanId = UUID.randomUUID();
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

        given(workflowCrudService.findOrphanCandidates(any())).willReturn(List.of(orphanId));
        given(workflowCrudService.findLatestVersion(orphanId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(buildDoc(null));

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService).deleteWorkflowById(orphanId);
    }

    // ─────────────────── 삭제 제외 대상 ─────────────────────────────────────

    @Test
    @DisplayName("nodes가 있는 워크플로우는 maxVersion==1이어도 삭제하지 않는다")
    void cleanupOrphanWorkflows_nonEmptyNodes_notDeleted() {
        UUID workflowId = UUID.randomUUID();
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

        given(workflowCrudService.findOrphanCandidates(any())).willReturn(List.of(workflowId));
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version))
            .willReturn(buildDoc(List.of(Map.of("type", "TRIGGER"))));

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService, never()).deleteWorkflowById(any());
    }

    @Test
    @DisplayName("PG 후보 목록이 비어있으면 MongoDB 조회도 삭제도 실행하지 않는다")
    void cleanupOrphanWorkflows_noCandidates_noOperation() {
        given(workflowCrudService.findOrphanCandidates(any())).willReturn(List.of());

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService, never()).findLatestVersion(any());
        verify(workflowCrudService, never()).deleteWorkflowById(any());
    }

    @Test
    @DisplayName("버전이 없는 워크플로우는 빈 것으로 간주하여 삭제한다")
    void cleanupOrphanWorkflows_noVersion_deleted() {
        UUID orphanId = UUID.randomUUID();

        given(workflowCrudService.findOrphanCandidates(any())).willReturn(List.of(orphanId));
        given(workflowCrudService.findLatestVersion(orphanId)).willReturn(Optional.empty());

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService).deleteWorkflowById(orphanId);
    }

    // ─────────────────── 예외 격리 ──────────────────────────────────────────

    @Test
    @DisplayName("한 워크플로우 처리 중 예외가 발생해도 나머지는 계속 처리한다")
    void cleanupOrphanWorkflows_exceptionOnOne_continuesOthers() {
        UUID failId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

        given(workflowCrudService.findOrphanCandidates(any()))
            .willReturn(List.of(failId, successId));
        given(workflowCrudService.findLatestVersion(failId))
            .willThrow(new RuntimeException("MongoDB 연결 오류"));
        given(workflowCrudService.findLatestVersion(successId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(buildDoc(List.of()));

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService, never()).deleteWorkflowById(failId);
        verify(workflowCrudService).deleteWorkflowById(successId);
    }

    @Test
    @DisplayName("여러 후보 중 nodes가 있는 것만 선별하여 빈 것만 삭제한다")
    void cleanupOrphanWorkflows_mixedCandidates_deletesOnlyBlank() {
        UUID blankId = UUID.randomUUID();
        UUID filledId = UUID.randomUUID();
        WorkflowVersion blankVersion = Mockito.mock(WorkflowVersion.class);
        WorkflowVersion filledVersion = Mockito.mock(WorkflowVersion.class);

        given(workflowCrudService.findOrphanCandidates(any()))
            .willReturn(List.of(blankId, filledId));
        given(workflowCrudService.findLatestVersion(blankId)).willReturn(Optional.of(blankVersion));
        given(workflowCrudService.loadDefinition(blankVersion)).willReturn(buildDoc(List.of()));
        given(workflowCrudService.findLatestVersion(filledId)).willReturn(Optional.of(filledVersion));
        given(workflowCrudService.loadDefinition(filledVersion))
            .willReturn(buildDoc(List.of(Map.of("type", "TRIGGER"))));

        scheduler.cleanupOrphanWorkflows();

        verify(workflowCrudService).deleteWorkflowById(blankId);
        verify(workflowCrudService, never()).deleteWorkflowById(filledId);
    }

    // ─────────────────── 고립 RUNNING 실행 sweeper ──────────────────────────

    @Test
    @DisplayName("임계 미만 RUNNING은 조회 대상에 들지 않는다 — 재시도 소진 중인 장시간 실행 오판 방지")
    void failStuckRunningExecutions_belowThreshold_notTransitioned() {
        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        given(workflowExecutionService.findStuckRunningExecutionIds(thresholdCaptor.capture()))
            .willReturn(List.of());

        scheduler.failStuckRunningExecutions();

        // AI 노드가 재시도(3회 × agent 타임아웃 180초)를 소진하며 40분째 도는 정상 실행은
        // startedAt이 임계 시각보다 뒤에 있어 `startedAt < threshold` 조건에 걸리지 않는다.
        LocalDateTime healthyLongRunStartedAt = LocalDateTime.now().minusMinutes(40);
        assertThat(healthyLongRunStartedAt).isAfter(thresholdCaptor.getValue());

        verify(workflowExecutionService, never()).markAsFailed(any(), any());
        verify(executionEventPublisher, never()).complete(any());
    }

    @Test
    @DisplayName("임계 초과 RUNNING은 사유를 남기고 FAILED로 확정하며 SSE 스트림을 닫는다")
    void failStuckRunningExecutions_aboveThreshold_marksFailed() {
        UUID stuckId = UUID.randomUUID();
        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        given(workflowExecutionService.findStuckRunningExecutionIds(thresholdCaptor.capture()))
            .willReturn(List.of(stuckId));

        scheduler.failStuckRunningExecutions();

        // 3시간 전 시작한 실행은 기본 임계(2시간)를 넘겨 조회 조건에 걸린다.
        assertThat(LocalDateTime.now().minusHours(3)).isBefore(thresholdCaptor.getValue());

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowExecutionService).markAsFailed(eq(stuckId), reasonCaptor.capture());
        // 사용자가 "왜 실패로 바뀌었는지" 알 수 있어야 한다 — 사유는 비어 있으면 안 된다.
        assertThat(reasonCaptor.getValue()).contains("프로세스 이상 종료 추정");
        verify(executionEventPublisher).complete(stuckId);
    }

    @Test
    @DisplayName("고립 실행이 없으면 확정도 스트림 정리도 하지 않는다")
    void failStuckRunningExecutions_noStuckExecutions_noOperation() {
        given(workflowExecutionService.findStuckRunningExecutionIds(any())).willReturn(List.of());

        scheduler.failStuckRunningExecutions();

        verify(workflowExecutionService, never()).markAsFailed(any(), any());
        verify(executionEventPublisher, never()).complete(any());
    }

    @Test
    @DisplayName("한 실행 확정 중 예외가 나도 나머지는 계속 확정한다")
    void failStuckRunningExecutions_exceptionOnOne_continuesOthers() {
        UUID failId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();

        given(workflowExecutionService.findStuckRunningExecutionIds(any()))
            .willReturn(List.of(failId, successId));
        Mockito.doThrow(new RuntimeException("DB 연결 오류"))
            .when(workflowExecutionService).markAsFailed(eq(failId), any());

        scheduler.failStuckRunningExecutions();

        verify(workflowExecutionService).markAsFailed(eq(successId), any());
        verify(executionEventPublisher).complete(successId);
        verify(executionEventPublisher, never()).complete(failId);
    }

    // ─────────────────── 헬퍼 ──────────────────────────────────────────────

    private WorkflowDefinitionDocument buildDoc(List<Map<String, Object>> nodes) {
        return WorkflowDefinitionDocument.builder()
            .nodes(nodes)
            .edges(List.of())
            .createdAt(LocalDateTime.now())
            .build();
    }
}
