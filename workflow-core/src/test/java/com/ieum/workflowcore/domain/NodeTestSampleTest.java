package com.ieum.workflowcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.SampleStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NodeTestSampleTest {

    @Test
    @DisplayName("record는 이전 결과를 통째로 덮는다 — FAILED로 덮이면 이전 출력이 남지 않는다")
    void recordOverwritesEverything() {
        Workflow workflow = Workflow.builder().userId(UUID.randomUUID()).name("w").isActive(true).build();
        NodeTestSample sample = NodeTestSample.create(workflow, "node-1");
        LocalDateTime first = LocalDateTime.of(2026, 10, 6, 12, 0);
        sample.record(SampleStatus.SUCCESS, "{\"a\":1}", null, first);

        LocalDateTime second = first.plusMinutes(1);
        sample.record(SampleStatus.FAILED, null, "HTTP 422: bad", second);

        assertThat(sample.getWorkflow()).isSameAs(workflow);
        assertThat(sample.getNodeId()).isEqualTo("node-1");
        assertThat(sample.getStatus()).isEqualTo(SampleStatus.FAILED);
        assertThat(sample.getOutputJson()).isNull();
        assertThat(sample.getErrorMessage()).isEqualTo("HTTP 422: bad");
        assertThat(sample.getTestedAt()).isEqualTo(second);
    }
}
