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

    /**
     * 비밀번호 규칙. 비밀번호 재설정({@link ResetPasswordRequest})·변경도 같은 규칙을 쓴다.
     *
     * <p>출력 가능한 ASCII만 허용한다 — BCrypt는 72바이트를 넘는 입력의 encode를 거부(500)하므로,
     * 한글 등 멀티바이트 문자를 막아 72자 = 72바이트가 되게 한다.
     */
    public static final int PASSWORD_MAX_LENGTH = 72;
    public static final String PASSWORD_REGEX =
        "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?])[\\x20-\\x7E]+$";
    public static final String PASSWORD_MESSAGE =
        "비밀번호는 영문 대소문자, 숫자, 특수문자를 각 1자 이상 포함해야 하며 한글 등은 사용할 수 없습니다";

    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "비밀번호 (8~72자, 영문 대소문자·숫자·특수문자 각 1자 이상 포함, 한글 불가)", example = "Password1!")
    @NotBlank
    @Size(min = 8, max = PASSWORD_MAX_LENGTH)
    @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
    private String password;

    @Schema(description = "이름 (최대 100자)", example = "홍길동")
    @NotBlank
    @Size(max = 100)
    private String name;
}
