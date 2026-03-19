package com.ieum.api.credential.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.ai.credential.domain.Credential;
import com.ieum.ai.credential.domain.CredentialType;
import com.ieum.ai.credential.service.CredentialService;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CredentialControllerTest {

    @Mock private CredentialService credentialService;

    @InjectMocks
    private CredentialController credentialController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(credentialController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setValidator(validator)
                .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }

    // ===== POST /api/v1/credentials =====

    @Test
    void create_success_returns201() throws Exception {
        Credential credential = buildCredential();
        given(credentialService.create(eq(userId), eq(AiProvider.CLAUDE), eq(CredentialType.API_KEY),
                eq("My Claude Key"), eq("sk-ant-api03-testkey"))).willReturn(credential);

        mockMvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "CLAUDE",
                                "credentialType", "API_KEY",
                                "displayName", "My Claude Key",
                                "apiKey", "sk-ant-api03-testkey"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("CLAUDE"))
                .andExpect(jsonPath("$.data.keyHint").value("sk-ant...1234"))
                .andExpect(jsonPath("$.data.encryptedApiKey").doesNotExist());
    }

    @Test
    void create_invalidProvider_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "INVALID_PROVIDER",
                                "credentialType", "API_KEY",
                                "displayName", "My Key",
                                "apiKey", "sk-ant-api03-testkey"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    void create_blankApiKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "CLAUDE",
                                "credentialType", "API_KEY",
                                "displayName", "My Key",
                                "apiKey", ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    void create_displayNameTooLong_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "CLAUDE",
                                "credentialType", "API_KEY",
                                "displayName", "a".repeat(101),
                                "apiKey", "sk-ant-api03-testkey"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    // ===== GET /api/v1/credentials =====

    @Test
    void getList_success_returns200() throws Exception {
        given(credentialService.getByUserId(userId)).willReturn(List.of(buildCredential(), buildCredential()));

        mockMvc.perform(get("/api/v1/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getList_empty_returnsEmptyArray() throws Exception {
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ===== DELETE /api/v1/credentials/{id} =====

    @Test
    void delete_success_returns200() throws Exception {
        willDoNothing().given(credentialService).delete(credentialId, userId);

        mockMvc.perform(delete("/api/v1/credentials/" + credentialId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        willThrow(new CustomException(ErrorCode.NOT_FOUND))
                .given(credentialService).delete(credentialId, userId);

        mockMvc.perform(delete("/api/v1/credentials/" + credentialId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    }

    // ===== POST /api/v1/credentials/{id}/validate =====

    @Test
    void validate_validKey_returns200WithTrue() throws Exception {
        Credential credential = buildCredential();
        given(credentialService.validateCredential(credentialId, userId)).willReturn(true);
        given(credentialService.getByIdAndUserId(credentialId, userId)).willReturn(credential);

        mockMvc.perform(post("/api/v1/credentials/" + credentialId + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.provider").value("CLAUDE"));
    }

    @Test
    void validate_invalidKey_returns200WithFalse() throws Exception {
        Credential credential = buildCredential();
        given(credentialService.validateCredential(credentialId, userId)).willReturn(false);
        given(credentialService.getByIdAndUserId(credentialId, userId)).willReturn(credential);

        mockMvc.perform(post("/api/v1/credentials/" + credentialId + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false));
    }

    // ===== 헬퍼 =====

    private Credential buildCredential() {
        return Credential.builder()
                .userId(userId)
                .provider(AiProvider.CLAUDE)
                .credentialType(CredentialType.API_KEY)
                .displayName("My Claude Key")
                .encryptedApiKey("encrypted-key")
                .keyHint("sk-ant...1234")
                .isValid(true)
                .build();
    }
}
