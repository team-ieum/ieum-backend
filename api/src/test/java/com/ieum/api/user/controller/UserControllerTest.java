package com.ieum.api.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.user.dto.UserResponse;
import com.ieum.api.user.service.UserService;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID userId;
    private CustomUserDetails userDetails;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setValidator(validator)
                .build();

        userId = UUID.randomUUID();
        userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }

    // ===== GET /api/v1/users/me =====

    @Test
    void getMyProfile_success_returns200() throws Exception {
        UserResponse response = buildUserResponse("홍길동");
        given(userService.getMyProfile(userId)).willReturn(response);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.name").value("홍길동"));
    }

    @Test
    void getMyProfile_userNotFound_returns404() throws Exception {
        given(userService.getMyProfile(userId)).willThrow(new CustomException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    }

    // ===== PATCH /api/v1/users/me =====

    @Test
    void updateMyProfile_success_returns200() throws Exception {
        UserResponse response = buildUserResponse("새이름");
        given(userService.updateMyProfile(eq(userId), eq("새이름"))).willReturn(response);

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "새이름"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새이름"));
    }

    @Test
    void updateMyProfile_blankName_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    void updateMyProfile_nameTooLong_returns400() throws Exception {
        String longName = "가".repeat(101);
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", longName))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    // ===== DELETE /api/v1/users/me =====

    @Test
    void deleteMyAccount_success_returns200() throws Exception {
        willDoNothing().given(userService).deleteMyAccount(userId);

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void deleteMyAccount_userNotFound_returns404() throws Exception {
        willThrow(new CustomException(ErrorCode.NOT_FOUND)).given(userService).deleteMyAccount(userId);

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    }

    // ===== 헬퍼 =====

    private UserResponse buildUserResponse(String name) {
        return UserResponse.builder()
                .id(userId)
                .email("user@example.com")
                .name(name)
                .provider("LOCAL")
                .createdAt(LocalDateTime.now())
                .build();
    }
}
