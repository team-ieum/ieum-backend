package com.ieum.api.integration.options;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 공급원 선택·cursor 분리·오류 응답 모양을 고정한다 (IEUM-BE-71). 인증 필수는 SecurityConfig의 anyRequest().authenticated()가 맡는다. */
class IntegrationOptionControllerTest {

    private final OptionSource worksheets = mock(OptionSource.class);
    private final UUID userId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        given(worksheets.key()).willReturn("google.worksheets");
        IntegrationOptionController controller =
            new IntegrationOptionController(new IntegrationOptionService(List.of(worksheets)));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("cursor를 뺀 쿼리 파라미터 전부를 inputs로, cursor는 따로 넘긴다")
    void passesInputsAndCursorSeparately() throws Exception {
        given(worksheets.fetch(userId, Map.of("spreadsheet_id", "1Bxi"), "c1"))
            .willReturn(new OptionPage(List.of(new OptionItem("매출", "매출")), "c2"));

        mockMvc.perform(get("/api/v1/integrations/google/options/worksheets")
                .param("spreadsheet_id", "1Bxi")
                .param("cursor", "c1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].id").value("매출"))
            .andExpect(jsonPath("$.data.items[0].name").value("매출"))
            .andExpect(jsonPath("$.data.nextCursor").value("c2"));
    }

    @Test
    @DisplayName("cursor가 없으면 null로 넘긴다")
    void missingCursorIsNull() throws Exception {
        given(worksheets.fetch(userId, Map.of("spreadsheet_id", "1Bxi"), null))
            .willReturn(new OptionPage(List.of(), null));

        mockMvc.perform(get("/api/v1/integrations/google/options/worksheets").param("spreadsheet_id", "1Bxi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("알 수 없는 {app}/{resource}는 404 NOT_FOUND, 공급원을 부르지 않는다")
    void unknownSourceIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/google/options/calendars"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(worksheets, never()).fetch(any(), any(), any());
    }

    @Test
    @DisplayName("공급원의 GOOGLE_SCOPE_REQUIRED는 403과 code로 나간다 — FE가 증분 동의를 유도하는 신호")
    void scopeRequiredIs403WithCode() throws Exception {
        given(worksheets.fetch(any(), any(), any()))
            .willThrow(new CustomException(ErrorCode.GOOGLE_SCOPE_REQUIRED));

        mockMvc.perform(get("/api/v1/integrations/google/options/worksheets").param("spreadsheet_id", "1Bxi"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("GOOGLE_SCOPE_REQUIRED"));
    }
}
