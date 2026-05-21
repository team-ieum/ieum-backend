package com.ieum.api.config;

import com.ieum.auth.jwt.JwtTokenProvider;
import com.ieum.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 프레임에서 JWT를 검증하고 Principal을 설정하는 인터셉터.
 *
 * <p>클라이언트는 STOMP CONNECT 헤더에 {@code Authorization: Bearer {token}}을 포함해야 한다.
 * 인증 성공 시 Principal의 name은 {@code userId.toString()}으로 설정된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[WS] CONNECT 요청에 Authorization 헤더 없음");
            throw new MessagingException("Authorization 헤더가 필요합니다.");
        }

        String token = authHeader.substring(7);
        try {
            jwtTokenProvider.validateToken(token);
            UUID userId = jwtTokenProvider.getUserIdFromToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                List.of(new SimpleGrantedAuthority(role))
            );
            accessor.setUser(auth);
            log.debug("[WS] 인증 성공 — userId: {}", userId);
        } catch (CustomException e) {
            log.warn("[WS] JWT 인증 실패 — {}", e.getMessage());
            throw new MessagingException("WebSocket 인증 실패: " + e.getMessage());
        }

        return message;
    }
}
