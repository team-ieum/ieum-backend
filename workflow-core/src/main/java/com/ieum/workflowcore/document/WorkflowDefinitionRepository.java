package com.ieum.workflowcore.document;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowDefinitionRepository
        extends MongoRepository<WorkflowDefinitionDocument, String> {

    /**
     * Finds the definition document associated with the given WorkflowVersion UUID.
     * Used for reverse lookups from MongoDB back to PostgreSQL.
     */
    Optional<WorkflowDefinitionDocument> findByWorkflowVersionId(String workflowVersionId);

    /**
     * 특정 brand를 사용하는 노드를 가진 정의를 찾아, 정의별 MongoDB {@code _id}
     * ({@code mongoDefinitionId})와 해당 brand 사용 노드 수({@code usedNodeCount})를 함께 반환한다.
     *
     * <p>{@code nodes[].config.brand}는 중첩 배열이지만 dot-notation으로 매칭된다.
     * 반환되는 {@code mongoDefinitionId}는 PostgreSQL {@code WorkflowVersion.mongoDefinitionId}와
     * 조인하는 키다(문서의 {@code workflowVersionId} 필드는 PG와 매칭되지 않으므로 쓰지 않는다).
     * 연동 서비스별 워크플로우 목록 조회의 1단계에 사용된다.
     */
    @Aggregation(pipeline = {
        "{ $match: { 'nodes.config.brand': ?0 } }",
        "{ $project: { _id: 0, mongoDefinitionId: { $toString: '$_id' }, "
            + "usedNodeCount: { $size: { $filter: { "
            + "input: { $ifNull: ['$nodes', []] }, as: 'n', "
            + "cond: { $eq: ['$$n.config.brand', ?0] } } } } } }"
    })
    List<BrandVersionCount> aggregateVersionCountsByBrand(String brand);
}
