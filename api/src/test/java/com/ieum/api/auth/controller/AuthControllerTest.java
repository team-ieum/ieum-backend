package com.ieum.api.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.dto.TokenInfo;
import com.ieum.auth.domain.VerificationPurpose;
import com.ieum.auth.service.AuthService;
import com.ieum.auth.service.EmailVerificationService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ===== POST /api/v1/auth/register =====

    @Test
    void register_success_returns201() throws Exception {
        User savedUser = buildUser();
        given(authService.register(anyString(), anyString(), anyString())).willReturn(savedUser);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "password", "Password1!",
                                "name", "홍길동"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("user@example.com"));
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "not-an-email",
                                "password", "Password1!",
                                "name", "홍길동"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    void register_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "password", "weakpass",
                                "name", "홍길동"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void register_duplicateEmail_returns400() throws Exception {
        given(authService.register(anyString(), anyString(), anyString()))
                .willThrow(new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "dup@example.com",
                                "password", "Password1!",
                                "name", "홍길동"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_ALREADY_EXISTS.name()));
    }

    // ===== POST /api/v1/auth/login =====

    @Test
    void login_success_returns200WithTokens() throws Exception {
        TokenInfo tokenInfo = new TokenInfo("access-token", "refresh-token", 1800L);
        given(authService.login(anyString(), anyString())).willReturn(tokenInfo);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "password", "Password1!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800));
    }

    @Test
    void login_invalidCredentials_returns400() throws Exception {
        given(authService.login(anyString(), anyString()))
                .willThrow(new CustomException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "password", "WrongPass1!"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_CREDENTIALS.name()));
    }

    @Test
    void login_missingField_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "user@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    // ===== POST /api/v1/auth/refresh =====

    @Test
    void refresh_success_returns200() throws Exception {
        TokenInfo tokenInfo = new TokenInfo("new-access-token", "refresh-token", 1800L);
        given(authService.refresh(anyString())).willReturn(tokenInfo);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"));
    }

    @Test
    void refresh_expiredToken_returns401() throws Exception {
        given(authService.refresh(anyString()))
                .willThrow(new CustomException(ErrorCode.TOKEN_EXPIRED));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "expired-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_EXPIRED.name()));
    }

    // ===== POST /api/v1/auth/logout =====

    @Test
    void logout_success_returns200() throws Exception {
        willDoNothing().given(authService).logout(anyString());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ===== 헬퍼 =====

    // ===== POST /api/v1/auth/email/send-code =====

    @Test
    void sendCode_success_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "purpose", "SIGNUP"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(emailVerificationService).should().sendCode("user@example.com", VerificationPurpose.SIGNUP);
    }

    @Test
    void sendCode_unknownPurpose_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "purpose", "LOGIN"
                        ))))
                .andExpect(status().isBadRequest());

        then(emailVerificationService).shouldHaveNoInteractions();
    }

    @Test
    void sendCode_tooSoon_returns429() throws Exception {
        willThrow(new CustomException(ErrorCode.VERIFICATION_RESEND_TOO_SOON))
                .given(emailVerificationService).sendCode(anyString(), any());

        mockMvc.perform(post("/api/v1/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "purpose", "PASSWORD_RESET"
                        ))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.VERIFICATION_RESEND_TOO_SOON.name()));
    }

    // ===== POST /api/v1/auth/email/verify-code =====

    @Test
    void verifyCode_success_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "code", "123456",
                                "purpose", "SIGNUP"
                        ))))
                .andExpect(status().isOk());

        then(emailVerificationService).should()
                .verifyCode("user@example.com", "123456", VerificationPurpose.SIGNUP);
    }

    @Test
    void verifyCode_notSixDigits_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "code", "12a456",
                                "purpose", "SIGNUP"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));

        then(emailVerificationService).shouldHaveNoInteractions();
    }

    // ===== POST /api/v1/auth/password/reset =====

    @Test
    void resetPassword_success_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "newPassword", "NewPassword1!"
                        ))))
                .andExpect(status().isOk());

        then(authService).should().resetPassword("user@example.com", "NewPassword1!");
    }

    @Test
    void resetPassword_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "newPassword", "weakpass"
                        ))))
                .andExpect(status().isBadRequest());

        then(authService).should(never()).resetPassword(anyString(), anyString());
    }

    @Test
    void resetPassword_notVerified_returns400() throws Exception {
        willThrow(new CustomException(ErrorCode.EMAIL_NOT_VERIFIED))
                .given(authService).resetPassword(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "newPassword", "NewPassword1!"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_NOT_VERIFIED.name()));
    }

    private User buildUser() {
        return User.builder()
                .email("user@example.com")
                .passwordHash("encoded-password")
                .name("홍길동")
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ROLE_USER)
                .build();
    }
}
