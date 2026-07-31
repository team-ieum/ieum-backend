package com.ieum.api.workflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.workflow.service.ExecutionRetryService;
import com.ieum.api.workflow.service.WorkflowService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.executable.ExecutableValidator;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * {@code WorkflowController.getExecutions}의 {@code size} 파라미터 {@code @Min}/{@code @Max}
 * 제약을 검증한다. 실제 요청 경로(스프링 AOP 프록시 + {@code @Validated})까지 태우려면
 * 전체 컨텍스트 기동이 필요하므로, Bean Validation의 {@link ExecutableValidator}로
 * 어노테이션이 실제 제약 위반을 만드는지만 직접 검증한다.
 */
class WorkflowControllerValidationTest {

    private final ExecutableValidator executableValidator =
        Validation.buildDefaultValidatorFactory().getValidator().forExecutables();

    private final WorkflowController controller = new WorkflowController(mock(WorkflowService.class), mock(ExecutionRetryService.class));
    private final CustomUserDetails userDetails =
        CustomUserDetails.of(UUID.randomUUID(), "user@example.com", "ROLE_USER");

    private Method getExecutionsMethod() throws NoSuchMethodException {
        return WorkflowController.class.getMethod("getExecutions",
            CustomUserDetails.class, UUID.class, ExecutionStatus.class,
            LocalDateTime.class, LocalDateTime.class, String.class, int.class);
    }

    @Test
    @DisplayName("size=0이면 @Min(1) 위반")
    void size_zero_violatesMin() throws Exception {
        Object[] args = {userDetails, UUID.randomUUID(), null, null, null, null, 0};

        Set<ConstraintViolation<WorkflowController>> violations =
            executableValidator.validateParameters(controller, getExecutionsMethod(), args);

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("size=101이면 @Max(100) 위반")
    void size_aboveMax_violatesMax() throws Exception {
        Object[] args = {userDetails, UUID.randomUUID(), null, null, null, null, 101};

        Set<ConstraintViolation<WorkflowController>> violations =
            executableValidator.validateParameters(controller, getExecutionsMethod(), args);

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("size=20이면 제약 위반 없음")
    void size_valid_noViolation() throws Exception {
        Object[] args = {userDetails, UUID.randomUUID(), null, null, null, null, 20};

        Set<ConstraintViolation<WorkflowController>> violations =
            executableValidator.validateParameters(controller, getExecutionsMethod(), args);

        assertThat(violations).isEmpty();
    }

    /**
     * 실제 요청 경로(HTTP 상태 코드)까지 검증한다. {@code standaloneSetup}에 컨트롤러를 그대로
     * 넣으면 {@code @Validated}가 만드는 AOP 프록시가 없어 제약이 전혀 걸리지 않으므로,
     * {@link MethodValidationPostProcessor}로 실제와 동일한 프록시를 직접 만들어 감싼다 —
     * 전체 스프링 컨텍스트 기동 없이도 "@Validated 위반 → ConstraintViolationException →
     * GlobalExceptionHandler → 400" 경로 전체를 태운다.
     */
    @org.junit.jupiter.api.Nested
    class HttpStatusTest {

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            MethodValidationPostProcessor postProcessor = new MethodValidationPostProcessor();
            // WorkflowController가 WorkflowControllerDocs를 구현하므로 기본(JDK 프록시)으로는
            // @GetMapping이 붙은 구현 클래스가 아니라 인터페이스 타입으로만 노출되어
            // standaloneSetup이 핸들러 매핑을 못 찾는다 — 클래스 기반(CGLIB) 프록시를 강제한다.
            postProcessor.setProxyTargetClass(true);
            postProcessor.afterPropertiesSet();
            Object validatedController = postProcessor.postProcessAfterInitialization(
                new WorkflowController(mock(WorkflowService.class), mock(ExecutionRetryService.class)), "workflowController");

            mockMvc = MockMvcBuilders.standaloneSetup(validatedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

            CustomUserDetails principal = CustomUserDetails.of(UUID.randomUUID(), "user@example.com", "ROLE_USER");
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        }

        @Test
        @DisplayName("GET .../executions?size=101 -> 400 (이전엔 200으로 통과하던 회귀)")
        void size_aboveMax_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/workflows/{id}/executions", UUID.randomUUID())
                    .param("size", "101"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET .../executions?size=0 -> 400")
        void size_zero_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/workflows/{id}/executions", UUID.randomUUID())
                    .param("size", "0"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET .../executions?size=20 -> 200")
        void size_valid_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/workflows/{id}/executions", UUID.randomUUID())
                    .param("size", "20"))
                .andExpect(status().isOk());
        }
    }
}
