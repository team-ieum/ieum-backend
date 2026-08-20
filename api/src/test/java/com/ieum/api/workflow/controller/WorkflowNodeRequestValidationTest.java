package com.ieum.api.workflow.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.workflow.service.ExecutionRetryService;
import com.ieum.api.workflow.service.WorkflowService;
import com.ieum.auth.security.CustomUserDetails;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 노드/엣지 요청 본문 계약을 검증한다 (IEUM-BE-60).
 *
 * <p>허용되지 않은 노드 {@code type}이 실행 시점까지 살아남던 것, 빈 {@code source}/{@code target}
 * 엣지가 통과하던 것을 생성 시점 400으로 막는다.
 *
 * <p>{@code description}과 {@code position}은 권장하되 <b>선택</b> 필드다 — 프론트가 보내지 않으므로
 * 필수로 두면 저장이 전부 막힌다. 다만 {@code position}을 보냈다면 {@code x}·{@code y}가 모두 있어야
 * 한다(반쪽 좌표는 400).
 */
class WorkflowNodeRequestValidationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new WorkflowController(
                mock(WorkflowService.class), mock(ExecutionRetryService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        CustomUserDetails principal =
            CustomUserDetails.of(UUID.randomUUID(), "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void expectStatus(String body, int expected) throws Exception {
        mockMvc.perform(post("/api/v1/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is(expected));
    }

    /** 노드 하나짜리 최소 유효 요청. 각 테스트는 이 본문에서 한 군데만 무너뜨린다. */
    private String body(String node, String edges) {
        return """
            {
              "name": "계약 검증 워크플로우",
              "description": "노드 계약 검증용",
              "nodes": [%s],
              "edges": [%s]
            }""".formatted(node, edges);
    }

    private static final String VALID_NODE = """
        { "id": "node-trigger", "type": "TRIGGER", "label": "시작",
          "description": "실행하면 시작해요.",
          "position": { "x": 40, "y": 120 }, "config": {} }""";

    @Test
    @DisplayName("description·position이 모두 있으면 201")
    void fullNode_returns201() throws Exception {
        expectStatus(body(VALID_NODE, ""), 201);
    }


    /**
     * 프론트가 실제로 보내는 모양이다 — {@code CreateWorkflowNodeDto}는 id·type·label·config 넷뿐이라
     * description도 position도 담기지 않는다. 이 테스트가 깨지면 워크플로우 저장이 통째로 막힌다.
     *
     * <p>description·position 각각의 누락을 따로 단언하던 테스트 둘을 이 하나로 합쳤다 — 셋 다 같은
     * 사실을 확인했고 이것이 그 상위 집합이다.
     */
    @Test
    @DisplayName("프론트 최소 노드(id·type·label·config)면 201")
    void frontendMinimalNode_returns201() throws Exception {
        String node = """
            { "id": "node-trigger", "type": "TRIGGER", "label": "시작", "config": {} }""";
        expectStatus(body(node, ""), 201);
    }

    @Test
    @DisplayName("position.y 누락이면 400 — 중첩 객체까지 검증된다")
    void missingPositionY_returns400() throws Exception {
        String node = """
            { "id": "node-trigger", "type": "TRIGGER", "label": "시작",
              "description": "실행하면 시작해요.",
              "position": { "x": 40 }, "config": {} }""";
        expectStatus(body(node, ""), 400);
    }

    @Test
    @DisplayName("허용되지 않은 노드 type이면 400 — 실행 시점이 아니라 생성 시점에 걸린다")
    void unknownNodeType_returns400() throws Exception {
        String node = """
            { "id": "node-x", "type": "SLACK", "label": "슬랙",
              "description": "슬랙으로 보내요.",
              "position": { "x": 40, "y": 120 }, "config": {} }""";
        expectStatus(body(node, ""), 400);
    }

    @Test
    @DisplayName("엣지 source가 비어 있으면 400")
    void blankEdgeSource_returns400() throws Exception {
        String edge = """
            { "source": "", "target": "node-trigger" }""";
        expectStatus(body(VALID_NODE, edge), 400);
    }

    @Test
    @DisplayName("config에 문자열·숫자·boolean·배열·객체가 섞여 있어도 201")
    void mixedConfigValueTypes_returns201() throws Exception {
        String node = """
            { "id": "node-http", "type": "HTTP", "label": "외부 호출",
              "description": "외부 서비스를 호출해요.",
              "position": { "x": 40, "y": 120 },
              "config": {
                "method": "POST",
                "url": "https://example.com/hook",
                "timeoutSeconds": 30,
                "followRedirects": true,
                "tags": ["a", "b"],
                "headers": { "X-Trace": "1" }
              }}""";
        expectStatus(body(node, ""), 201);
    }
}
