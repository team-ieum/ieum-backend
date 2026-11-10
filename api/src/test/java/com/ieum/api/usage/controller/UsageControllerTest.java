package com.ieum.api.usage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.usage.dto.TokenUsageResponse;
import com.ieum.api.usage.dto.TokenUsageResponse.DailyUsage;
import com.ieum.api.usage.dto.TokenUsageResponse.ModelUsage;
import com.ieum.api.usage.dto.TokenUsageResponse.ProviderUsage;
import com.ieum.api.usage.service.TokenUsageService;
import com.ieum.auth.security.CustomUserDetails;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    @Mock private TokenUsageService tokenUsageService;

    @InjectMocks
    private UsageController usageController;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // standalone은 Boot 자동 설정이 없어 LocalDate가 배열로 나간다 — Boot 기본값(ISO 문자열)과 맞춘다
        mockMvc = MockMvcBuilders.standaloneSetup(usageController)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @Test
    void getTokenUsage_passesDatesAndSerializesContract() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        given(tokenUsageService.getTokenUsage(userId, from, to)).willReturn(new TokenUsageResponse(from, to,
            List.of(new ProviderUsage("CLAUDE", "Anthropic Claude", 100, 30, 130, 2,
                List.of(new ModelUsage("claude-sonnet-5-5", "Claude Sonnet 5.5", true, true, 100, 30, 130, 2)))),
            List.of(new DailyUsage(from, "CLAUDE", "claude-sonnet-5-5", 100, 30, 130, 2))));

        mockMvc.perform(get("/api/v1/usage/tokens").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.providers[0].provider").value("CLAUDE"))
                .andExpect(jsonPath("$.data.providers[0].models[0].isDefault").value(true))
                .andExpect(jsonPath("$.data.providers[0].models[0].inCatalog").value(true))
                .andExpect(jsonPath("$.data.providers[0].models[0].callCount").value(2))
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.daily[0].totalTokens").value(130));
    }

    @Test
    void getTokenUsage_noParams_passesNulls() throws Exception {
        given(tokenUsageService.getTokenUsage(userId, null, null))
            .willReturn(new TokenUsageResponse(LocalDate.now(), LocalDate.now(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/usage/tokens")).andExpect(status().isOk());
    }

    @Test
    void getTokenUsage_badDateFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/usage/tokens").param("from", "2026/10/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(tokenUsageService, never()).getTokenUsage(any(), any(), any());
    }
}
