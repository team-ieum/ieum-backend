package com.ieum.api.common;

import com.ieum.common.dto.ApiResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.ieum.workflowcore.service.TestSampleMissingException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void handleCustomException_returnsMatchingHttpStatus() throws Exception {
        mockMvc.perform(get("/test/custom-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()))
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND.getMessage()));
    }

    @Test
    void handleCustomException_withDetailMessage_returnsDetailMessage() throws Exception {
        mockMvc.perform(get("/test/custom-exception-detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()))
                .andExpect(jsonPath("$.message").value("상세 오류 메시지"));
    }

    @Test
    void handleValidationException_returns400WithInvalidInputCode() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void handleTypeMismatch_returns400WithParamName() throws Exception {
        mockMvc.perform(get("/test/type-mismatch").param("value", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()))
                .andExpect(jsonPath("$.message").value("잘못된 파라미터: value"));
    }

    @Test
    void handleUnhandledException_returns500() throws Exception {
        mockMvc.perform(get("/test/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_SERVER_ERROR.name()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }

    @Test
    void unknownPath_returns404NotFound() throws Exception {
        mockMvc.perform(get("/test/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()))
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND.getMessage()));
    }

    // standalone MockMvc는 NoHandlerFoundException을 던지지만 실제 앱은 정적 리소스 핸들러가 NoResourceFoundException을 던진다.
    @Test
    void noResourceFoundException_returns404NotFound() {
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
                .handleException(new NoResourceFoundException(HttpMethod.GET, "api/v1/x"), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND.name());
    }

    // 웹훅 토큰 주소는 경로 자체가 비밀 — 4xx 경고·[UNHANDLED] 로그에 토큰이 남으면 안 된다(IEUM-BE-91)
    @Test
    void hookTokenPath_isMaskedInLogs(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/hooks/SECRET-TOKEN-123/x");
        request.setServletPath("/api/v1/hooks/SECRET-TOKEN-123/x");
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        handler.handleException(new NoResourceFoundException(HttpMethod.POST, "api/v1/hooks/SECRET-TOKEN-123/x"), request);
        handler.handleException(new IllegalStateException("boom"), request);

        assertThat(output).doesNotContain("SECRET-TOKEN-123").contains("/api/v1/hooks/***");
        assertThat(GlobalExceptionHandler.loggableUri(new MockHttpServletRequest("GET", "/api/v1/webhooks/abc")))
                .isEqualTo("/api/v1/webhooks/abc");
    }

    // 비예약 문자를 퍼센트 인코딩해도 MVC·Security는 hooks로 매칭한다 — 판정은 디코드된 servletPath 기준이어야 한다
    @Test
    void encodedHookTokenPath_isMaskedInLogs(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/%68ooks/SECRET-TOKEN-123");
        request.setServletPath("/api/v1/hooks/SECRET-TOKEN-123");

        new GlobalExceptionHandler().handleException(
                new NoResourceFoundException(HttpMethod.POST, "api/v1/hooks/SECRET-TOKEN-123"), request);

        assertThat(GlobalExceptionHandler.loggableUri(request)).isEqualTo("/api/v1/hooks/***");
        assertThat(output).doesNotContain("SECRET-TOKEN-123");
    }

    @Test
    void unsupportedMethod_returns405WithInvalidInputCode() throws Exception {
        mockMvc.perform(delete("/test/unhandled"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    void handleTestSampleMissing_returns400WithMissingNodeIdsInData() throws Exception {
        mockMvc.perform(get("/test/sample-missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.TEST_SAMPLE_MISSING.name()))
                .andExpect(jsonPath("$.data.missingNodeIds[0]").value("node-a"))
                .andExpect(jsonPath("$.data.missingNodeIds[1]").value("node-b"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/custom-exception")
        public void customException() {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }

        @GetMapping("/custom-exception-detail")
        public void customExceptionWithDetail() {
            throw new CustomException(ErrorCode.INVALID_INPUT, "상세 오류 메시지");
        }

        @GetMapping("/type-mismatch")
        public void typeMismatch(@RequestParam Integer value) {
        }

        @PostMapping("/validation")
        public void validation(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/unhandled")
        public void unhandled() {
            throw new RuntimeException("예상치 못한 오류");
        }

        @GetMapping("/sample-missing")
        public void sampleMissing() {
            throw new TestSampleMissingException(List.of("node-a", "node-b"));
        }

        static class TestRequest {
            @NotBlank
            private String name;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
        }
    }
}
