package com.ieum.api.oauth.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.api.oauth.service.OAuthConnectionService;
import com.ieum.auth.security.CustomUserDetails;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OAuthConnectionControllerTest {

    @Mock private OAuthConnectionService oauthConnectionService;
    @InjectMocks private OAuthConnectionController oauthConnectionController;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(oauthConnectionController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }

    @Test
    @DisplayName("연동된 OAuth 계정 목록 조회 성공 시 200 응답과 목록을 반환한다")
    void getConnections_Success_Returns200WithList() throws Exception {
        UUID connectionId = UUID.randomUUID();
        OAuthConnectionResponse response = OAuthConnectionResponse.builder()
            .id(connectionId)
            .provider("GOOGLE")
            .providerAccountId("google-sub-id")
            .scopes(List.of("email", "profile"))
            .createdAt(LocalDateTime.now())
            .build();

        when(oauthConnectionService.getConnections(userId)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/oauth/connections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].id").value(connectionId.toString()))
            .andExpect(jsonPath("$.data[0].provider").value("GOOGLE"))
            .andExpect(jsonPath("$.data[0].providerAccountId").value("google-sub-id"))
            .andExpect(jsonPath("$.data[0].scopes[0]").value("email"))
            .andExpect(jsonPath("$.data[0].scopes[1]").value("profile"));
    }
}
