package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ieum.workflowcore.document.BrandVersionCount;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService.BrandWorkflowPage;
import com.querydsl.core.Tuple;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private final String mongoId = "6a282dfc42e3a69df23ec991";

    @Test
    @DisplayName("사용자 워크플로우가 없으면 Mongo를 조회하지 않고 빈 페이지를 반환한다")
    void findByBrand_noOwnedWorkflow_skipsMongo() {
        // given — findOwnedLatestMongoDefinitionIds 미스텁 = 빈 목록
        // when
        BrandWorkflowPage result = service.findByBrand(userId, "discord", 0, 20);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        verifyNoInteractions(definitionRepository);
    }

    @Test
    @DisplayName("Mongo 집계는 사용자 소유 최신 정의 id를 ObjectId로 변환해 넘기고, 형식 오류 id는 건너뛴다")
    @SuppressWarnings("unchecked")
    void findByBrand_passesOwnedIdsAsObjectIds() {
        // given
        String otherId = "6a282dfc42e3a69df23ec992";
        given(workflowQueryRepository.findOwnedLatestMongoDefinitionIds(userId))
            .willReturn(List.of(mongoId, "not-an-object-id", otherId));

        // when
        service.findByBrand(userId, "discord", 0, 20);

        // then
        ArgumentCaptor<List<ObjectId>> captor = ArgumentCaptor.forClass(List.class);
        verify(definitionRepository).aggregateVersionCountsByBrand(captor.capture(), eq("discord"));
        // 제네릭 소거로 List<String>이 들어와도 컴파일되므로 런타임 원소 타입을 직접 확인한다
        List<?> captured = captor.getValue();
        assertThat(captured).hasOnlyElementsOfType(ObjectId.class);
        assertThat(captor.getValue()).containsExactly(new ObjectId(mongoId), new ObjectId(otherId));
    }

    @Test
    @DisplayName("Mongo 매칭 결과가 없으면 빈 페이지를 반환하고 PG 페이지 조회를 하지 않는다")
    void findByBrand_noMatch_returnsEmpty() {
        // given
        given(workflowQueryRepository.findOwnedLatestMongoDefinitionIds(userId)).willReturn(List.of(mongoId));
        given(definitionRepository.aggregateVersionCountsByBrand(List.of(new ObjectId(mongoId)), "discord"))
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
        givenBrandMatch("discord", 2);

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

        // size(20)보다 적게(1개) 반환 → hasNext=false
        given(workflowQueryRepository.findOwnedLatestVersions(userId, List.of(mongoId), 0, 20))
            .willReturn(List.of(tuple));

        // when
        BrandWorkflowPage result = service.findByBrand(userId, "discord", 0, 20);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).workflow().getName()).isEqualTo("디스코드 알림 봇");
        assertThat(result.items().get(0).usedNodeCount()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("size+1개가 조회되면 hasNext=true이고 초과분은 잘라낸다")
    void findByBrand_sizePlusOne_hasNextTrueAndTrimmed() {
        // given — size=1 요청에 2개(size+1) 반환
        givenBrandMatch("slack", 1);

        Workflow workflow = Workflow.builder().userId(userId).name("w").isActive(true).build();
        WorkflowVersion version = WorkflowVersion.builder().id(UUID.randomUUID()).version(1)
            .mongoDefinitionId(mongoId).build();
        Tuple firstRow = mock(Tuple.class);
        given(firstRow.get(0, Workflow.class)).willReturn(workflow);
        given(firstRow.get(1, WorkflowVersion.class)).willReturn(version);
        Tuple overflowRow = mock(Tuple.class); // 초과분 — subList로 잘려 매핑되지 않음

        given(workflowQueryRepository.findOwnedLatestVersions(userId, List.of(mongoId), 0, 1))
            .willReturn(List.of(firstRow, overflowRow));

        // when
        BrandWorkflowPage result = service.findByBrand(userId, "slack", 0, 1);

        // then
        assertThat(result.hasNext()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    private void givenBrandMatch(String brand, int usedNodeCount) {
        given(workflowQueryRepository.findOwnedLatestMongoDefinitionIds(userId)).willReturn(List.of(mongoId));
        BrandVersionCount count = mock(BrandVersionCount.class);
        given(count.getMongoDefinitionId()).willReturn(mongoId);
        given(count.getUsedNodeCount()).willReturn(usedNodeCount);
        given(definitionRepository.aggregateVersionCountsByBrand(List.of(new ObjectId(mongoId)), brand))
            .willReturn(List.of(count));
    }
}
