package com.ieum.workflowcore.service;

import com.ieum.workflowcore.document.BrandVersionCount;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.querydsl.core.Tuple;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연동 서비스(brand)별 워크플로우 목록 조회 로직.
 *
 * <p>2단계 조회:
 * <ol>
 *   <li>MongoDB — {@code nodes[].config.brand} 매칭으로 해당 brand를 쓰는 정의의
 *       {@code workflowVersionId} + 사용 노드 수를 집계</li>
 *   <li>PostgreSQL — 수집한 versionId 중 각 워크플로우의 <b>최신 버전</b>이면서 요청자 소유인
 *       워크플로우만 페이징 조회 (소유권은 이 단계에서 강제)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationWorkflowQueryService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowQueryRepository workflowQueryRepository;

    public BrandWorkflowPage findByBrand(UUID userId, String brand, int page, int size) {
        // 1단계 — MongoDB: brand 사용 정의의 mongoDefinitionId(_id) + usedNodeCount
        List<BrandVersionCount> counts = definitionRepository.aggregateVersionCountsByBrand(brand);
        if (counts.isEmpty()) {
            return new BrandWorkflowPage(List.of(), false);
        }

        Map<String, Integer> usedNodeCountByMongoId = counts.stream()
            .collect(Collectors.toMap(
                BrandVersionCount::getMongoDefinitionId,
                BrandVersionCount::getUsedNodeCount,
                (a, b) -> a));

        List<String> mongoDefinitionIds = List.copyOf(usedNodeCountByMongoId.keySet());

        // 2단계 — PostgreSQL: 최신 버전 + 소유권 필터 페이징
        // size+1개를 조회해 별도 count 쿼리 없이 hasNext를 판별하고, 초과분(1개)은 잘라낸다.
        List<Tuple> rows = workflowQueryRepository.findOwnedLatestVersions(userId, mongoDefinitionIds, page, size);
        boolean hasNext = rows.size() > size;
        List<Tuple> pageRows = hasNext ? rows.subList(0, size) : rows;

        List<ServiceWorkflow> items = pageRows.stream()
            .map(row -> {
                Workflow workflow = row.get(0, Workflow.class);
                WorkflowVersion version = row.get(1, WorkflowVersion.class);
                int usedNodeCount = usedNodeCountByMongoId.getOrDefault(version.getMongoDefinitionId(), 0);
                return new ServiceWorkflow(workflow, usedNodeCount);
            })
            .toList();

        log.debug("[IntegrationWorkflowQueryService] brand: {}, 결과: {}건", brand, items.size());
        return new BrandWorkflowPage(items, hasNext);
    }

    /** 워크플로우 + 해당 brand 사용 노드 수 */
    public record ServiceWorkflow(Workflow workflow, int usedNodeCount) {}

    /** brand별 워크플로우 페이지 결과 */
    public record BrandWorkflowPage(List<ServiceWorkflow> items, boolean hasNext) {}
}
