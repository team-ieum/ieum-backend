package com.ieum.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.exception.SuccessCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final String code;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(SuccessCode.OK.getMessage())
                .code(SuccessCode.OK.name())
                .build();
    }

    public static ApiResponse<Void> ok() {
        return ApiResponse.<Void>builder()
                .success(true)
                .message(SuccessCode.OK.getMessage())
                .code(SuccessCode.OK.name())
                .build();
    }

    public static <T> ApiResponse<T> created(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(SuccessCode.CREATED.getMessage())
                .code(SuccessCode.CREATED.name())
                .build();
    }

    public static <T> ApiResponse<T> error(ErrorCode code) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(code.getMessage())
                .code(code.name())
                .build();
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .code(code.name())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
