package com.ieum.common.dto;

import com.ieum.common.exception.ErrorCode;
import com.ieum.common.exception.SuccessCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void ok_withData_returnsSuccessWithData() {
        ApiResponse<String> response = ApiResponse.ok("hello");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("hello");
        assertThat(response.getMessage()).isEqualTo(SuccessCode.OK.getMessage());
        assertThat(response.getCode()).isEqualTo(SuccessCode.OK.name());
    }

    @Test
    void ok_withoutData_returnsSuccessWithNullData() {
        ApiResponse<Void> response = ApiResponse.ok();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).isEqualTo(SuccessCode.OK.getMessage());
        assertThat(response.getCode()).isEqualTo(SuccessCode.OK.name());
    }

    @Test
    void created_returnsCreatedResponse() {
        ApiResponse<String> response = ApiResponse.created("new-resource");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("new-resource");
        assertThat(response.getMessage()).isEqualTo(SuccessCode.CREATED.getMessage());
        assertThat(response.getCode()).isEqualTo(SuccessCode.CREATED.name());
    }

    @Test
    void error_withErrorCode_returnsErrorResponse() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.NOT_FOUND);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
        assertThat(response.getCode()).isEqualTo(ErrorCode.NOT_FOUND.name());
    }

    @Test
    void error_withErrorCodeAndCustomMessage_usesCustomMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_INPUT, "name 필드가 비어 있습니다.");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("name 필드가 비어 있습니다.");
        assertThat(response.getCode()).isEqualTo(ErrorCode.INVALID_INPUT.name());
    }

    @Test
    void error_withStringMessage_codeIsNull() {
        ApiResponse<Void> response = ApiResponse.error("알 수 없는 오류");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("알 수 없는 오류");
        assertThat(response.getCode()).isNull();
    }

    @Test
    void error_withData_carriesDataCodeAndMessage() {
        ApiResponse<java.util.Map<String, java.util.List<String>>> response = ApiResponse.error(
            ErrorCode.TEST_SAMPLE_MISSING, "먼저 테스트할 노드가 있습니다.",
            java.util.Map.of("missingNodeIds", java.util.List.of("a", "b")));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).containsEntry("missingNodeIds", java.util.List.of("a", "b"));
        assertThat(response.getMessage()).isEqualTo("먼저 테스트할 노드가 있습니다.");
        assertThat(response.getCode()).isEqualTo(ErrorCode.TEST_SAMPLE_MISSING.name());
    }

    @Test
    void testSampleErrorCodes_haveContractStatuses() {
        assertThat(ErrorCode.TEST_SAMPLE_MISSING.getStatus().value()).isEqualTo(400);
        assertThat(ErrorCode.TEST_SAMPLE_NOT_FOUND.getStatus().value()).isEqualTo(404);
    }
}
