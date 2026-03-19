package com.ieum.api.auth.dto;

import com.ieum.auth.domain.User;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegisterResponse {

    private UUID userId;
    private String email;
    private String name;

    public static RegisterResponse from(User user) {
        return RegisterResponse.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .name(user.getName())
            .build();
    }
}
