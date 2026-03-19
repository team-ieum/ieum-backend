package com.ieum.api.user.dto;

import com.ieum.auth.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "사용자 정보 응답")
@Getter
@Builder
public class UserResponse {

    @Schema(description = "사용자 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "이름", example = "홍길동")
    private String name;

    @Schema(description = "로그인 제공자 (LOCAL, GOOGLE)", example = "LOCAL")
    private String provider;

    @Schema(description = "가입일시", example = "2026-01-01T00:00:00")
    private LocalDateTime createdAt;

    public static UserResponse from(User user) {
        return UserResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .name(user.getName())
            .provider(user.getProvider().name())
            .createdAt(user.getCreatedAt())
            .build();
    }
}
