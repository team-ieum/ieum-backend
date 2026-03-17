package com.ieum.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomExceptionTest {

    @Test
    void constructor_withErrorCode_usesErrorCodeMessage() {
        CustomException ex = new CustomException(ErrorCode.NOT_FOUND);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
    }

    @Test
    void constructor_withDetailMessage_usesDetailMessage() {
        CustomException ex = new CustomException(ErrorCode.WORKFLOW_NOT_FOUND, "워크플로우 ID: abc-123을 찾을 수 없습니다.");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("워크플로우 ID: abc-123을 찾을 수 없습니다.");
    }

    @Test
    void constructor_withCause_chainsCause() {
        RuntimeException cause = new RuntimeException("원인 예외");
        CustomException ex = new CustomException(ErrorCode.TOKEN_REFRESH_FAILED, cause);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOKEN_REFRESH_FAILED);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.TOKEN_REFRESH_FAILED.getMessage());
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    void isRuntimeException() {
        CustomException ex = new CustomException(ErrorCode.UNAUTHORIZED);

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
