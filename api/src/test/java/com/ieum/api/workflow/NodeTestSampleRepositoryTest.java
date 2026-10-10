package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.domain.NodeTestSample;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.SampleStatus;
import com.ieum.workflowcore.repository.NodeTestSampleRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 H2 스키마(ddl-auto create-drop)에서 UNIQUE 제약·파생 쿼리·JPQL 삭제가 동작하는지 본다.
 * 러너의 upsert 경합 처리({@code DataIntegrityViolationException} 재시도)가 이 위반 타입에 기댄다.
 * 컨텍스트 구성은 {@code IeumApplicationTest}와 같다(테스트엔 Redis가 없다).
 */
@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
@Transactional
class NodeTestSampleRepositoryTest {

    @TestConfiguration
    static class NoRedisConfig {

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
            org.mockito.BDDMockito.given(factory.getConnection())
                .willThrow(new RedisConnectionFailureException("테스트 환경엔 Redis가 없다"));
            return factory;
        }
    }

    @Autowired private NodeTestSampleRepository sampleRepository;
    @Autowired private WorkflowRepository workflowRepository;

    private Workflow newWorkflow() {
        return workflowRepository.save(
            Workflow.builder().userId(UUID.randomUUID()).name("w").isActive(true).build());
    }

    private NodeTestSample sample(Workflow workflow, String nodeId, String outputJson) {
        NodeTestSample sample = NodeTestSample.create(workflow, nodeId);
        sample.record(SampleStatus.SUCCESS, outputJson, null, LocalDateTime.now());
        return sample;
    }

    @Test
    @DisplayName("(workflow, node) 쌍은 하나뿐 — 중복 저장은 DataIntegrityViolationException (upsert 경합의 신호)")
    void duplicateWorkflowNodePairViolatesUnique() {
        Workflow workflow = newWorkflow();
        sampleRepository.saveAndFlush(sample(workflow, "node-1", "{}"));

        assertThatThrownBy(() -> sampleRepository.saveAndFlush(sample(workflow, "node-1", "{\"x\":1}")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("조회는 워크플로우로 범위가 갈린다 — 같은 nodeId라도 남의 워크플로우 샘플은 안 보인다")
    void lookupsAreScopedByWorkflow() {
        Workflow mine = newWorkflow();
        Workflow other = newWorkflow();
        sampleRepository.saveAndFlush(sample(mine, "n1", "{\"owner\":\"mine\"}"));
        sampleRepository.saveAndFlush(sample(mine, "n2", "{}"));
        sampleRepository.saveAndFlush(sample(other, "n1", "{\"owner\":\"other\"}"));

        assertThat(sampleRepository.findByWorkflowIdAndNodeId(mine.getId(), "n1"))
            .get().extracting(NodeTestSample::getOutputJson).isEqualTo("{\"owner\":\"mine\"}");
        assertThat(sampleRepository.findByWorkflowIdAndNodeId(mine.getId(), "nope")).isEmpty();
        assertThat(sampleRepository.findByWorkflowIdAndNodeIdIn(mine.getId(), List.of("n1", "n2", "n3")))
            .extracting(NodeTestSample::getNodeId).containsExactlyInAnyOrder("n1", "n2");
    }

    @Test
    @DisplayName("deleteByWorkflow는 그 워크플로우의 샘플만 지운다")
    void deleteByWorkflowRemovesOnlyThatWorkflowsSamples() {
        Workflow mine = newWorkflow();
        Workflow other = newWorkflow();
        sampleRepository.saveAndFlush(sample(mine, "n1", "{}"));
        sampleRepository.saveAndFlush(sample(other, "n1", "{}"));

        sampleRepository.deleteByWorkflow(mine);

        assertThat(sampleRepository.findByWorkflowIdAndNodeId(mine.getId(), "n1")).isEmpty();
        assertThat(sampleRepository.findByWorkflowIdAndNodeId(other.getId(), "n1")).isPresent();
    }
}
