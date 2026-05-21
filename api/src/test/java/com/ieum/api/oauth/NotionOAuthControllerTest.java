package com.ieum.api.oauth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.auth.domain.NotionOAuthState;
import com.ieum.auth.repository.NotionOAuthStateRepository;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Optional;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class NotionOAuthControllerTest {

    @Mock private NotionOAuthService notionOAuthService;
    @Mock private NotionOAuthStateRepository notionOAuthStateRepository;
    @InjectMocks private NotionOAuthController notionOAuthController;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(notionOAuthController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );

        ReflectionTestUtils.setField(notionOAuthController, "notionAuthUrl", "https://api.notion.com/v1/oauth/authorize");
        ReflectionTestUtils.setField(notionOAuthController, "notionClientId", "test-client-id");
        ReflectionTestUtils.setField(notionOAuthController, "notionRedirectUri", "http://localhost/callback");
        ReflectionTestUtils.setField(notionOAuthController, "frontendRedirectUri", "http://localhost:3000/settings");
    }

    @Test
    @DisplayName("인가 URL 요청 시 302 리다이렉트와 state 파라미터가 포함된다")
    void authorizeNotion_Success_Redirects302() throws Exception {
        mockMvc.perform(get("/api/v1/notion/oauth2/authorize"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", startsWith("https://api.notion.com/v1/oauth/authorize")))
            .andExpect(header().string("Location", containsString("state=")));
    }

    @Test
    @DisplayName("유효한 code와 state로 콜백 시 302로 프론트엔드에 리다이렉트한다")
    void notionCallback_Success_Redirects302() throws Exception {
        when(notionOAuthStateRepository.findById("valid-state"))
            .thenReturn(Optional.of(buildState("valid-state")));

        mockMvc.perform(get("/api/v1/notion/oauth2/callback")
                .param("code", "valid-code")
                .param("state", "valid-state"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:3000/settings"));
    }

    @Test
    @DisplayName("유효하지 않은 state로 콜백 시 에러 리다이렉트로 응답한다")
    void notionCallback_Fail_InvalidState_RedirectsWithError() throws Exception {
        when(notionOAuthStateRepository.findById("bad-state"))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/notion/oauth2/callback")
                .param("code", "valid-code")
                .param("state", "bad-state"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", containsString("error=notion_state_expired")));
    }

    @Test
    @DisplayName("Notion error 파라미터가 있으면 에러 리다이렉트로 응답한다")
    void notionCallback_Fail_NotionErrorParam_RedirectsWithError() throws Exception {
        when(notionOAuthStateRepository.findById("valid-state"))
            .thenReturn(Optional.of(buildState("valid-state")));

        mockMvc.perform(get("/api/v1/notion/oauth2/callback")
                .param("state", "valid-state")
                .param("error", "access_denied"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", containsString("error=notion_oauth_failed")));

        verify(notionOAuthStateRepository).delete(any(NotionOAuthState.class));
    }

    @Test
    @DisplayName("code 파라미터가 없으면 에러 리다이렉트로 응답한다")
    void notionCallback_Fail_NoCode_RedirectsWithError() throws Exception {
        when(notionOAuthStateRepository.findById("valid-state"))
            .thenReturn(Optional.of(buildState("valid-state")));

        mockMvc.perform(get("/api/v1/notion/oauth2/callback")
                .param("state", "valid-state"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", containsString("error=notion_oauth_failed")));
    }

    @Test
    @DisplayName("서비스 예외 발생 시 token_failed 에러 리다이렉트로 응답한다")
    void notionCallback_Fail_ServiceException_RedirectsWithError() throws Exception {
        when(notionOAuthStateRepository.findById("valid-state"))
            .thenReturn(Optional.of(buildState("valid-state")));
        doThrow(new CustomException(ErrorCode.TOKEN_REFRESH_FAILED))
            .when(notionOAuthService).handleCallback(anyString(), any(UUID.class));

        mockMvc.perform(get("/api/v1/notion/oauth2/callback")
                .param("code", "valid-code")
                .param("state", "valid-state"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", containsString("error=notion_token_failed")));
    }

    private NotionOAuthState buildState(String state) {
        return NotionOAuthState.builder()
            .state(state)
            .userId(userId)
            .ttl(300L)
            .build();
    }
}
