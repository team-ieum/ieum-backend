package com.ieum.api.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "크레덴셜 등록 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateCredentialRequest {

    @Schema(description = "AI 프로바이더", example = "CLAUDE", allowableValues = {"CLAUDE", "OPENAI", "GEMINI"})
    @NotBlank
    private String provider;

    @Schema(description = "크레덴셜 타입", example = "API_KEY", allowableValues = {"API_KEY", "OAUTH"})
    @NotBlank
    private String credentialType;

    @Schema(description = "표시 이름 (최대 100자)", example = "내 Claude API Key")
    @NotBlank
    @Size(max = 100)
    private String displayName;

    @Schema(description = "API 키 원문 (등록 후 조회 불가)", example = "sk-ant-api03-...")
    @NotBlank
    private String apiKey;
}
