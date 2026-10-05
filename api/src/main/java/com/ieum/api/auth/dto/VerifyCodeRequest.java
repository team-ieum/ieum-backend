package com.ieum.api.auth.dto;

import com.ieum.auth.domain.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "이메일 인증코드 검증 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyCodeRequest {

    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "메일로 받은 6자리 숫자 인증코드", example = "482913")
    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "인증코드는 6자리 숫자입니다")
    private String code;

    @Schema(description = "인증 용도 — 코드 발송 때와 같아야 한다", example = "SIGNUP")
    @NotNull
    private VerificationPurpose purpose;
}
