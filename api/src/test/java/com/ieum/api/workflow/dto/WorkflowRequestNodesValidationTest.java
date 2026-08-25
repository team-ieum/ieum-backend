package com.ieum.api.workflow.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code nodes} 리스트의 <b>원소</b> {@code @NotNull} 제약을 검증한다 (IEUM-BE-65).
 *
 * <p>필드에만 {@code @NotNull}을 붙이면 리스트 자체만 검증해 {@code "nodes":[null]}이 통과하고,
 * 그 null이 서비스까지 흘러 저장 경로마다 방어 코드를 요구했다. 제약을 요청 경계로 옮긴 뒤
 * 그 방어들을 지웠으므로, 이 테스트가 깨지면 지워진 방어가 필요해진다.
 */
class WorkflowRequestNodesValidationTest {

    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NODES_WITH_NULL = """
        {"name":"워크플로우","nodes":[null],"edges":[]}
        """;
    private static final String VALID_NODES = """
        {"name":"워크플로우","nodes":[{"id":"n1","type":"AI","label":"라벨"}],"edges":[]}
        """;

    @Test
    @DisplayName("생성 요청의 nodes에 null 원소가 있으면 제약 위반이다")
    void 생성_요청의_null_노드는_위반이다() throws Exception {
        CreateWorkflowRequest request =
            objectMapper.readValue(NODES_WITH_NULL, CreateWorkflowRequest.class);

        assertThat(violationPaths(request)).contains("nodes[0].<list element>");
    }

    @Test
    @DisplayName("수정 요청의 nodes에 null 원소가 있으면 제약 위반이다")
    void 수정_요청의_null_노드는_위반이다() throws Exception {
        UpdateWorkflowRequest request =
            objectMapper.readValue(NODES_WITH_NULL, UpdateWorkflowRequest.class);

        assertThat(violationPaths(request)).contains("nodes[0].<list element>");
    }

    @Test
    @DisplayName("정상 노드만 담긴 수정 요청은 위반이 없다")
    void 정상_요청은_위반이_없다() throws Exception {
        UpdateWorkflowRequest request =
            objectMapper.readValue(VALID_NODES, UpdateWorkflowRequest.class);

        assertThat(violationPaths(request)).isEmpty();
    }

    private Set<String> violationPaths(Object request) {
        return validator.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .collect(java.util.stream.Collectors.toSet());
    }
}
