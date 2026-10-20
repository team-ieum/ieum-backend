package com.ieum.api.oauth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.api.oauth.service.OAuthConnectionService;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
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

    @Test
    @DisplayName("연동 해제 성공 시 200, 토큰의 사용자 ID로 해제한다")
    void disconnect_Success_Returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/oauth/connections/GOOGLE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(oauthConnectionService).disconnect(userId, AuthProvider.GOOGLE);
    }

    @Test
    @DisplayName("연동되지 않은 provider 해제 시 400 ACCOUNT_NOT_CONNECTED")
    void disconnect_NotConnected_Returns400() throws Exception {
        doThrow(new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED))
            .when(oauthConnectionService).disconnect(userId, AuthProvider.NOTION);

        mockMvc.perform(delete("/api/v1/oauth/connections/NOTION"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ErrorCode.ACCOUNT_NOT_CONNECTED.name()));
    }

    @Test
    @DisplayName("지원하지 않는 provider 값이면 400 INVALID_INPUT, 서비스 호출 없음")
    void disconnect_UnknownProvider_Returns400() throws Exception {
        mockMvc.perform(delete("/api/v1/oauth/connections/SLACK"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));

        verify(oauthConnectionService, never()).disconnect(any(), any());
    }
}
