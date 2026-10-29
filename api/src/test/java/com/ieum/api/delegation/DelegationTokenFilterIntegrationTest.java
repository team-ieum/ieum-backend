package com.ieum.api.delegation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.api.integration.options.IntegrationFieldService;
import com.ieum.api.integration.options.IntegrationOptionService;
import com.ieum.api.oauth.service.OAuthConnectionService;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.api.workflow.service.NodeTestService;
import com.ieum.auth.delegation.DelegationTokenService;
import com.ieum.auth.delegation.DelegationTokenService.Delegation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 위임 토큰 필터를 실제 시큐리티 체인(+ {@code @PreAuthorize hasRole('USER')})에 태운다 (IEUM-BE-106).
 * 토큰 저장소는 목으로 두고 "이 원문은 이 위임"만 정한다. 대상 서비스도 목 — 컨트롤러까지 토큰 userId가 오는지만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class DelegationTokenFilterIntegrationTest {

    private static final String HEADER = "X-Delegation-Token";
    private static final String TOKEN = "deleg-raw";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DelegationTokenService delegationTokenService;
    @MockitoBean private OAuthConnectionService oauthConnectionService;
    @MockitoBean private NodeTestService nodeTestService;
    @MockitoBean private IntegrationOptionService integrationOptionService;
    @MockitoBean private IntegrationFieldService integrationFieldService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(delegationTokenService.resolve(anyString())).willAnswer(inv ->
            TOKEN.equals(inv.getArgument(0)) ? Optional.of(new Delegation(userId, "ROLE_USER", workflowId)) : Optional.empty());
        given(oauthConnectionService.getConnections(any())).willReturn(List.of());
        given(nodeTestService.test(any(), any(), any(), any())).willReturn(NodeTestResponse.listening(LocalDateTime.now()));
    }

    @Test
    @DisplayName("허용 경로(사용자 범위) — 토큰 사용자로 인증돼 컨트롤러가 그 userId로 부른다")
    void userScopedPath_authenticatesAsTokenUser() throws Exception {
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, TOKEN))
            .andExpect(status().isOk());

        verify(oauthConnectionService).getConnections(userId);
    }

    @Test
    @DisplayName("허용 경로(워크플로우 범위) — 경로 workflowId가 토큰 것과 같으면 통과")
    void workflowScopedPath_sameWorkflow_passes() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{wid}/nodes/{nid}/test", workflowId, "node-1").header(HEADER, TOKEN))
            .andExpect(status().isOk());

        verify(nodeTestService).test(eq(userId), eq(workflowId), eq("node-1"), any());
    }

    @Test
    @DisplayName("허용 경로 나머지 셋(옵션·칸·샘플)도 통과 — 매처 오타·메서드 오류면 agent 도구가 운영에서 전부 401이 된다")
    void otherAllowedPaths_pass() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/{app}/options/{resource}", "slack", "channels").header(HEADER, TOKEN))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/integrations/{app}/fields/{resource}", "notion", "properties").header(HEADER, TOKEN))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workflows/{wid}/nodes/{nid}/sample", workflowId, "node-1").header(HEADER, TOKEN))
            .andExpect(status().isOk());

        verify(integrationOptionService).fetch(eq(userId), eq("slack"), eq("channels"), any());
        verify(integrationFieldService).getFields(eq(userId), eq("notion"), eq("properties"), any());
        verify(nodeTestService).getSample(userId, workflowId, "node-1");
    }

    @Test
    @DisplayName("워크플로우 범위 경로의 workflowId가 토큰 것과 다르면 403 — 서비스에 닿지 않는다")
    void workflowScopedPath_otherWorkflow_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{wid}/nodes/{nid}/test", UUID.randomUUID(), "node-1").header(HEADER, TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(nodeTestService);
    }

    @Test
    @DisplayName("허용 경로 밖(다른 경로·같은 경로의 다른 메서드)은 401")
    void outsideAllowList_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/workflows").header(HEADER, TOKEN))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(delete("/api/v1/oauth/connections/GOOGLE").header(HEADER, TOKEN))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/workflows/{wid}/nodes/{nid}/test", workflowId, "node-1").header(HEADER, TOKEN))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(oauthConnectionService, nodeTestService);
    }

    @Test
    @DisplayName("모르는 토큰(만료·폐기·위조)과 빈 값은 허용 경로여도 401")
    void unknownOrBlankToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, "expired"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, ""))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(oauthConnectionService);
    }

    @Test
    @DisplayName("공백뿐인 헤더는 토큰 조회 없이 401")
    void whitespaceToken_unauthorizedWithoutResolve() throws Exception {
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, "   "))
            .andExpect(status().isUnauthorized());

        verify(delegationTokenService, never()).resolve(any());
        verifyNoInteractions(oauthConnectionService);
    }

    @Test
    @DisplayName("토큰의 role이 null·빈 문자열이면 401 — 권한 생성 예외로 500이 되지 않는다")
    void blankRole_unauthorized() throws Exception {
        given(delegationTokenService.resolve("null-role")).willReturn(Optional.of(new Delegation(userId, null, workflowId)));
        given(delegationTokenService.resolve("empty-role")).willReturn(Optional.of(new Delegation(userId, "", workflowId)));

        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, "null-role"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, "empty-role"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(oauthConnectionService);
    }
}
