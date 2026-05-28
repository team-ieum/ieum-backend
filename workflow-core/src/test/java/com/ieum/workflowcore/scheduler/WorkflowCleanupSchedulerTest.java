package com.ieum.workflowcore.scheduler;

import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCleanupScheduler 단위 테스트")
class WorkflowCleanupSchedulerTest {

    @Mock private WorkflowCrudService workflowCrudService;

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

    // ─────────────────── 헬퍼 ──────────────────────────────────────────────

    private WorkflowDefinitionDocument buildDoc(List<Map<String, Object>> nodes) {
        return WorkflowDefinitionDocument.builder()
            .nodes(nodes)
            .edges(List.of())
            .createdAt(LocalDateTime.now())
            .build();
    }
}
