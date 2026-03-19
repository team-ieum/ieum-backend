package com.ieum.api.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "크레덴셜 유효성 검증 응답")
@Getter
@Builder
public class ValidateCredentialResponse {

    @Schema(description = "API 키 유효 여부", example = "true")
    private boolean isValid;

    @Schema(description = "AI 프로바이더", example = "CLAUDE")
    private String provider;
}
