package com.ieum.api.user.dto;

import com.ieum.api.auth.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "비밀번호 변경 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @Schema(description = "현재 비밀번호", example = "Password1!")
    @NotBlank
    private String currentPassword;

    @Schema(description = "새 비밀번호 (8~100자, 영문 대소문자·숫자·특수문자 각 1자 이상 포함)", example = "NewPassword1!")
    @NotBlank
    @Size(min = 8, max = 100)
    @Pattern(regexp = RegisterRequest.PASSWORD_REGEX, message = RegisterRequest.PASSWORD_MESSAGE)
    private String newPassword;
}
