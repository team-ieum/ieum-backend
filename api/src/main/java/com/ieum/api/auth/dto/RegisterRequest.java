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

    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "비밀번호 (8~100자, 영문 대소문자·숫자·특수문자 각 1자 이상 포함)", example = "Password1!")
    @NotBlank
    @Size(min = 8, max = 100)
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+$",
        message = "비밀번호는 영문 대소문자, 숫자, 특수문자를 각 1자 이상 포함해야 합니다"
    )
    private String password;

    @Schema(description = "이름 (최대 100자)", example = "홍길동")
    @NotBlank
    @Size(max = 100)
    private String name;
}
