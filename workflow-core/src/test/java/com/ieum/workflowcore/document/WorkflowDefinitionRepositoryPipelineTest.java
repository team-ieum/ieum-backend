package com.ieum.workflowcore.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.util.json.ParameterBindingDocumentCodec;

/**
 * brand 집계 파이프라인의 파라미터 바인딩을 Mongo 없이 확인한다. 집계의 의미 자체는 실 Mongo가 있어야 보이지만
 * (로컬 e2e), 문자열 오타·바인딩 모양 오류는 여기서 잡힌다 — 이 레포엔 Mongo 통합 테스트 인프라가 없다.
 */
class WorkflowDefinitionRepositoryPipelineTest {

    private static final List<String> BRANDS = List.of("google", "gmail", "sheets");

    private Document[] boundStages() throws NoSuchMethodException {
        Method method = WorkflowDefinitionRepository.class
            .getMethod("aggregateVersionCountsByBrand", List.class, List.class);
        String[] pipeline = method.getAnnotation(Aggregation.class).pipeline();
        ParameterBindingDocumentCodec codec = new ParameterBindingDocumentCodec();
        Object[] args = {List.of(new ObjectId()), BRANDS};
        return new Document[] {codec.decode(pipeline[0], args), codec.decode(pipeline[1], args)};
    }

    @Test
    @DisplayName("$match는 nodes.config.brand를 brand 목록의 $in으로 거른다")
    void matchStageFiltersByBrandList() throws Exception {
        Document match = boundStages()[0].get("$match", Document.class);

        assertThat(match.get("nodes.config.brand", Document.class).get("$in")).isEqualTo(BRANDS);
    }

    @Test
    @DisplayName("$project의 usedNodeCount는 노드 brand가 목록에 든 노드만 센다")
    void projectStageCountsNodesWhoseBrandIsInList() throws Exception {
        Document filter = boundStages()[1].get("$project", Document.class)
            .get("usedNodeCount", Document.class).get("$size", Document.class).get("$filter", Document.class);

        assertThat(filter.get("cond", Document.class).get("$in"))
            .isEqualTo(List.of("$$n.config.brand", BRANDS));
    }
}
