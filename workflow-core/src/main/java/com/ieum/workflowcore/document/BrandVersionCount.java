package com.ieum.workflowcore.document;

/**
 * 특정 brand를 사용하는 워크플로우 정의의 집계 결과 projection.
 *
 * @see WorkflowDefinitionRepository#aggregateVersionCountsByBrand(String)
 */
public interface BrandVersionCount {

    /**
     * 매칭된 정의의 MongoDB {@code _id}(ObjectId hex 문자열).
     * PostgreSQL {@code WorkflowVersion.mongoDefinitionId}와 조인하는 키다.
     *
     * <p>주의: Mongo 문서의 {@code workflowVersionId} 필드는 정의 생성 시 임의 UUID가 채워지는
     * 죽은 필드로 PG와 매칭되지 않는다. 반드시 {@code _id} ↔ {@code mongoDefinitionId}로 조인한다.
     */
    String getMongoDefinitionId();

    /** 해당 정의에서 brand를 사용하는 노드 수 */
    int getUsedNodeCount();
}
