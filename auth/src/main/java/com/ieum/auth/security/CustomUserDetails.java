package com.ieum.auth.security;

import com.ieum.auth.domain.User;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final UUID id;
    private final String email;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public static CustomUserDetails from(User user) {
        return new CustomUserDetails(
            user.getId(),
            user.getEmail(),
            user.getPasswordHash() != null ? user.getPasswordHash() : "",
            Collections.singletonList(new SimpleGrantedAuthority(user.getRole().name()))
        );
    }

    public static CustomUserDetails of(UUID id, String email, String role) {
        return new CustomUserDetails(
            id,
            email,
            "",
            Collections.singletonList(new SimpleGrantedAuthority(role))
        );
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** 단일 권한(role) 문자열(예: "ROLE_TESTER")을 반환한다. agent로 전달할 X-User-Role 헤더 값에 사용한다. */
    public String getRole() {
        return authorities.stream()
            .findFirst()
            .map(GrantedAuthority::getAuthority)
            .orElse(null);
    }
}
