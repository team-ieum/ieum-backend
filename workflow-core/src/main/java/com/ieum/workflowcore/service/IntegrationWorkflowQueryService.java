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
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연동 서비스(brand)별 워크플로우 목록 조회 로직.
 *
 * <p>3단계 조회. PG {@code WorkflowVersion.mongoDefinitionId} ↔ Mongo {@code _id}로 조인한다
 * (Mongo 문서의 {@code workflowVersionId}는 PG와 매칭되지 않는 죽은 필드라 쓰지 않는다).
 * <ol>
 *   <li>PostgreSQL — 요청자 소유 워크플로우의 <b>최신 버전</b> {@code mongoDefinitionId} 목록.
 *       Mongo 문서엔 userId가 없어 사용자 범위는 여기서 정한다. 비면 Mongo를 부르지 않는다</li>
 *   <li>MongoDB — 그 id 중 {@code nodes[].config.brand}가 매칭되는 정의와 사용 노드 수를 집계</li>
 *   <li>PostgreSQL — 매칭된 id의 워크플로우를 페이징 조회 (소유권·최신 버전을 다시 강제)</li>
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
        // 1단계 — PostgreSQL: 요청자 소유 최신 버전의 정의 id. _id가 ObjectId라 변환해서 넘긴다
        List<ObjectId> ownedDefinitionIds = toObjectIds(
            workflowQueryRepository.findOwnedLatestMongoDefinitionIds(userId));
        if (ownedDefinitionIds.isEmpty()) {
            return new BrandWorkflowPage(List.of(), false);
        }

        // 2단계 — MongoDB: 그중 brand 사용 정의의 mongoDefinitionId(_id) + usedNodeCount
        List<BrandVersionCount> counts = definitionRepository.aggregateVersionCountsByBrand(ownedDefinitionIds, brand);
        if (counts.isEmpty()) {
            return new BrandWorkflowPage(List.of(), false);
        }

        Map<String, Integer> usedNodeCountByMongoId = counts.stream()
            .collect(Collectors.toMap(
                BrandVersionCount::getMongoDefinitionId,
                BrandVersionCount::getUsedNodeCount,
                (a, b) -> a));

        List<String> mongoDefinitionIds = List.copyOf(usedNodeCountByMongoId.keySet());

        // 3단계 — PostgreSQL: 최신 버전 + 소유권 필터 페이징 (IN절 크기 ≤ 사용자 워크플로우 수)
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

    private static List<ObjectId> toObjectIds(List<String> ids) {
        return ids.stream()
            .filter(id -> {
                if (ObjectId.isValid(id)) {
                    return true;
                }
                log.warn("[IntegrationWorkflowQueryService] ObjectId 형식이 아닌 mongoDefinitionId 건너뜀: {}", id);
                return false;
            })
            .map(ObjectId::new)
            .toList();
    }

    /** 워크플로우 + 해당 brand 사용 노드 수 */
    public record ServiceWorkflow(Workflow workflow, int usedNodeCount) {}

    /** brand별 워크플로우 페이지 결과 */
    public record BrandWorkflowPage(List<ServiceWorkflow> items, boolean hasNext) {}
}
