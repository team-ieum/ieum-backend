package com.ieum.api.delegation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.api.oauth.service.OAuthConnectionService;
import com.ieum.auth.delegation.DelegationTokenService;
import com.ieum.auth.delegation.DelegationTokenService.Delegation;
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

/** {@code ieum.agent.internal-token}이 설정되면 위임 요청에 {@code X-Internal-Token} 일치도 요구한다 (IEUM-BE-106). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = {
    "aes.secret-key=ieum-test-secret-key-32bytes-ok!",
    "ieum.agent.internal-token=" + DelegationTokenFilterInternalTokenTest.INTERNAL})
class DelegationTokenFilterInternalTokenTest {

    static final String INTERNAL = "internal-secret";
    private static final String HEADER = "X-Delegation-Token";
    private static final String TOKEN = "deleg-raw";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DelegationTokenService delegationTokenService;
    @MockitoBean private OAuthConnectionService oauthConnectionService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(delegationTokenService.resolve(anyString())).willAnswer(inv ->
            TOKEN.equals(inv.getArgument(0))
                ? Optional.of(new Delegation(userId, "ROLE_USER", UUID.randomUUID())) : Optional.empty());
        given(oauthConnectionService.getConnections(any())).willReturn(List.of());
    }

    @Test
    @DisplayName("X-Internal-Token이 없으면 토큰 조회 전에 401")
    void missing_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, TOKEN))
            .andExpect(status().isUnauthorized());

        verify(delegationTokenService, never()).resolve(any());
        verifyNoInteractions(oauthConnectionService);
    }

    @Test
    @DisplayName("X-Internal-Token이 다르면 401")
    void mismatch_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, TOKEN).header("X-Internal-Token", "wrong"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(oauthConnectionService);
    }

    @Test
    @DisplayName("X-Internal-Token이 같으면 통과")
    void match_passes() throws Exception {
        mockMvc.perform(get("/api/v1/oauth/connections").header(HEADER, TOKEN).header("X-Internal-Token", INTERNAL))
            .andExpect(status().isOk());

        verify(oauthConnectionService).getConnections(userId);
    }
}
