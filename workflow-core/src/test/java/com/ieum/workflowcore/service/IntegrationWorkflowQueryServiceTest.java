package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.workflowcore.document.BrandVersionCount;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService.BrandWorkflowPage;
import com.querydsl.core.Tuple;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationWorkflowQueryServiceTest {

    @Mock
    private WorkflowDefinitionRepository definitionRepository;

    @Mock
    private WorkflowQueryRepository workflowQueryRepository;

    @InjectMocks
    private IntegrationWorkflowQueryService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("Mongo 매칭 결과가 없으면 빈 페이지를 반환하고 PG를 조회하지 않는다")
    void findByBrand_noMatch_returnsEmpty() {
        // given
        given(definitionRepository.aggregateVersionCountsByBrand("discord"))
            .willReturn(List.of());

        // when
        BrandWorkflowPage result = service.findByBrand(userId, "discord", 0, 20);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        verify(workflowQueryRepository, never())
            .findOwnedLatestVersions(ArgumentMatchers.any(), ArgumentMatchers.anyList(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("brand 매칭 시 최신 버전 워크플로우와 usedNodeCount를 매핑해 반환한다")
    void findByBrand_match_mapsUsedNodeCount() {
        // given
        String mongoId = "6a282dfc42e3a69df23ec991";
        BrandVersionCount count = mock(BrandVersionCount.class);
        given(count.getMongoDefinitionId()).willReturn(mongoId);
        given(count.getUsedNodeCount()).willReturn(2);
        given(definitionRepository.aggregateVersionCountsByBrand("discord"))
            .willReturn(List.of(count));

        Workflow workflow = Workflow.builder()
            .userId(userId)
            .name("디스코드 알림 봇")
            .isActive(true)
            .build();
        WorkflowVersion version = WorkflowVersion.builder()
            .id(UUID.randomUUID())
            .version(3)
            .mongoDefinitionId(mongoId)
            .build();

        Tuple tuple = mock(Tuple.class);
        given(tuple.get(0, Workflow.class)).willReturn(workflow);
        given(tuple.get(1, WorkflowVersion.class)).willReturn(version);

        given(workflowQueryRepository.findOwnedLatestVersions(userId, List.of(mongoId), 0, 20))
            .willReturn(List.of(tuple));
        given(workflowQueryRepository.hasNextOwnedLatestVersions(userId, List.of(mongoId), 0, 20))
            .willReturn(false);

        // when
        BrandWorkflowPage result = service.findByBrand(userId, "discord", 0, 20);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).workflow().getName()).isEqualTo("디스코드 알림 봇");
        assertThat(result.items().get(0).usedNodeCount()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("PG 결과에 hasNext=true이면 그대로 전달한다")
    void findByBrand_hasNextPropagated() {
        // given
        String mongoId = "6a282dfc42e3a69df23ec991";
        BrandVersionCount count = mock(BrandVersionCount.class);
        given(count.getMongoDefinitionId()).willReturn(mongoId);
        given(count.getUsedNodeCount()).willReturn(1);
        given(definitionRepository.aggregateVersionCountsByBrand("slack"))
            .willReturn(List.of(count));

        Workflow workflow = Workflow.builder().userId(userId).name("w").isActive(true).build();
        WorkflowVersion version = WorkflowVersion.builder().id(UUID.randomUUID()).version(1)
            .mongoDefinitionId(mongoId).build();
        Tuple tuple = mock(Tuple.class);
        given(tuple.get(0, Workflow.class)).willReturn(workflow);
        given(tuple.get(1, WorkflowVersion.class)).willReturn(version);

        given(workflowQueryRepository.findOwnedLatestVersions(userId, List.of(mongoId), 0, 20))
            .willReturn(List.of(tuple));
        given(workflowQueryRepository.hasNextOwnedLatestVersions(userId, List.of(mongoId), 0, 20))
            .willReturn(true);

        // when
        BrandWorkflowPage result = service.findByBrand(userId, "slack", 0, 20);

        // then
        assertThat(result.hasNext()).isTrue();
    }
}
