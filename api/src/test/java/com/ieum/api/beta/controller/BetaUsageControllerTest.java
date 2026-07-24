package com.ieum.api.beta.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.beta.service.BetaQuotaService;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.auth.security.CustomUserDetails;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class BetaUsageControllerTest {

    @Mock private BetaQuotaService betaQuotaService;

    @InjectMocks
    private BetaUsageController betaUsageController;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(betaUsageController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }

    @Test
    void getUsage_success_returnsPercentageAndDailyCallsRemaining() throws Exception {
        given(betaQuotaService.getTokenUsagePercentage(userId)).willReturn(12.5);
        given(betaQuotaService.getRemainingDailyCalls(userId)).willReturn(18L);

        mockMvc.perform(get("/api/v1/beta/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.percentage").value(12.5))
                .andExpect(jsonPath("$.data.dailyCallsRemaining").value(18))
                .andExpect(jsonPath("$.data.usedTokens").doesNotExist())
                .andExpect(jsonPath("$.data.tokenBudget").doesNotExist());
    }

    @Test
    void getUsage_betaNotEligible_returnsZeroPercentageAndFullQuota() throws Exception {
        // 베타 비자격/미사용 사용자는 Redis에 키가 없어 BetaQuotaService가 자연스럽게 0%/전체 잔여를 반환한다.
        given(betaQuotaService.getTokenUsagePercentage(userId)).willReturn(0.0);
        given(betaQuotaService.getRemainingDailyCalls(userId)).willReturn(30L);

        mockMvc.perform(get("/api/v1/beta/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.percentage").value(0.0))
                .andExpect(jsonPath("$.data.dailyCallsRemaining").value(30));
    }
}
