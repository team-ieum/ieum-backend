package com.ieum.api.auth.dto;

import com.ieum.auth.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "회원가입 응답")
@Getter
@Builder
public class RegisterResponse {

    @Schema(description = "사용자 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "이름", example = "홍길동")
    private String name;

    public static RegisterResponse from(User user) {
        return RegisterResponse.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .name(user.getName())
            .build();
    }
}
