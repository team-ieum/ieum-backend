package com.ieum.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "비밀번호 재설정 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @Schema(description = "PASSWORD_RESET 인증을 마친 이메일", example = "user@example.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "새 비밀번호 (8~100자, 영문 대소문자·숫자·특수문자 각 1자 이상 포함)", example = "NewPassword1!")
    @NotBlank
    @Size(min = 8, max = 100)
    @Pattern(regexp = RegisterRequest.PASSWORD_REGEX, message = RegisterRequest.PASSWORD_MESSAGE)
    private String newPassword;
}
