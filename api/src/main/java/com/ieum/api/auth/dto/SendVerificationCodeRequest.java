package com.ieum.api.auth.dto;

import com.ieum.auth.domain.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "이메일 인증코드 발송 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SendVerificationCodeRequest {

    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "인증 용도", example = "SIGNUP")
    @NotNull
    private VerificationPurpose purpose;
}
