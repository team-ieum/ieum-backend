package com.ieum.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Schema(description = "OAuth 토큰 교환 요청")
@Getter
public class OAuthTokenExchangeRequest {

    @Schema(description = "일회용 인가 코드", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotBlank
    private String code;
}