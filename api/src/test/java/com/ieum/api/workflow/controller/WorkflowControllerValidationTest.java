package com.ieum.api.workflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ieum.api.workflow.service.WorkflowService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableValidator;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code WorkflowController.getExecutions}의 {@code size} 파라미터 {@code @Min}/{@code @Max}
 * 제약을 검증한다. 실제 요청 경로(스프링 AOP 프록시 + {@code @Validated})까지 태우려면
 * 전체 컨텍스트 기동이 필요하므로, Bean Validation의 {@link ExecutableValidator}로
 * 어노테이션이 실제 제약 위반을 만드는지만 직접 검증한다.
 */
class WorkflowControllerValidationTest {

    private final ExecutableValidator executableValidator =
        Validation.buildDefaultValidatorFactory().getValidator().forExecutables();

    private final WorkflowController controller = new WorkflowController(mock(WorkflowService.class));
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
}
