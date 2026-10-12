package com.ieum.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "회원가입 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    /** 비밀번호 규칙. 비밀번호 재설정({@link ResetPasswordRequest})도 같은 규칙을 쓴다. */
    public static final String PASSWORD_REGEX =
        "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+$";
    public static final String PASSWORD_MESSAGE = "비밀번호는 영문 대소문자, 숫자, 특수문자를 각 1자 이상 포함해야 합니다";

    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "비밀번호 (8~100자, 영문 대소문자·숫자·특수문자 각 1자 이상 포함)", example = "Password1!")
    @NotBlank
    @Size(min = 8, max = 100)
    @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
    private String password;

    @Schema(description = "이름 (최대 100자)", example = "홍길동")
    @NotBlank
    @Size(max = 100)
    private String name;
}
