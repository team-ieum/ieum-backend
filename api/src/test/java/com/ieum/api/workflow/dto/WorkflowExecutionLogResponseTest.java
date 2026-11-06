package com.ieum.api.workflow.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowExecutionLogResponseTest {

    @Test
    @DisplayName("from은 실행 로그의 detailJson을 그대로 옮긴다")
    void fromCopiesDetailJson() {
        WorkflowExecutionLog log = WorkflowExecutionLog.builder().nodeId("node-condition").nodeType(NodeType.CONDITION)
            .status(ExecutionLogStatus.SUCCESS).detailJson("{\"picked\":[\"b_7f3a9c21\"]}").build();

        assertThat(WorkflowExecutionLogResponse.from(log).getDetailJson()).isEqualTo("{\"picked\":[\"b_7f3a9c21\"]}");
    }
}
