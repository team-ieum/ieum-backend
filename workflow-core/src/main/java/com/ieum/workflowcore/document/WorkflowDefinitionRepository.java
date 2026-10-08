package com.ieum.workflowcore.document;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
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
     * 주어진 정의({@code _id} ∈ {@code definitionIds}) 중 brand 목록({@code brands}) 중 하나를 사용하는 노드를
     * 가진 것을 찾아, 정의별 MongoDB {@code _id}({@code mongoDefinitionId})와 해당 brand 사용 노드 수
     * ({@code usedNodeCount})를 함께 반환한다. 서비스 하나가 brand 여러 개로 저장되는 경우(GOOGLE =
     * google·gmail·sheets)를 위해 목록으로 받는다.
     *
     * <p>{@code _id}는 ObjectId로 저장돼 있다 — 파라미터 바인딩이 문자열을 변환해 주지 않으므로
     * {@link ObjectId}로 변환해 넘긴다. 문서에 userId가 없으므로 사용자 범위는 호출자가
     * PG에서 뽑은 id 목록으로 좁힌다.
     *
     * <p>{@code nodes[].config.brand}는 중첩 배열이지만 dot-notation으로 매칭된다.
     * 반환되는 {@code mongoDefinitionId}는 PostgreSQL {@code WorkflowVersion.mongoDefinitionId}와
     * 조인하는 키다(문서의 {@code workflowVersionId} 필드는 PG와 매칭되지 않으므로 쓰지 않는다).
     * 연동 서비스별 워크플로우 목록 조회의 2단계에 사용된다.
     */
    @Aggregation(pipeline = {
        "{ $match: { _id: { $in: ?0 }, 'nodes.config.brand': { $in: ?1 } } }",
        "{ $project: { _id: 0, mongoDefinitionId: { $toString: '$_id' }, "
            + "usedNodeCount: { $size: { $filter: { "
            + "input: { $ifNull: ['$nodes', []] }, as: 'n', "
            + "cond: { $in: ['$$n.config.brand', ?1] } } } } } }"
    })
    List<BrandVersionCount> aggregateVersionCountsByBrand(List<ObjectId> definitionIds, List<String> brands);
}
