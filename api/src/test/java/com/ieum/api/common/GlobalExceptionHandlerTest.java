package com.ieum.api.common;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

        static class TestRequest {
            @NotBlank
            private String name;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
        }
    }
}
