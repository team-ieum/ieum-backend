package com.ieum.api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 설정.
 *
 * <h3>클라이언트 사용 방법</h3>
 * <ol>
 *   <li>STOMP 연결: {@code /ws} (SockJS 지원)</li>
 *   <li>STOMP CONNECT 헤더: {@code Authorization: Bearer {JWT}}</li>
 *   <li>구독: {@code /user/queue/chat/stream} — 스트리밍 토큰 수신</li>
 *   <li>메시지 전송: {@code /app/chat/{workflowId}} — ChatRequest 전송</li>
 * </ol>
 *
 * <h3>응답 흐름</h3>
 * <pre>
 *   { type: "token", content: "...", index: 0 }   ← 토큰 단위 스트리밍
 *   { type: "token", content: "...", index: 1 }
 *   ...
 *   { type: "complete", totalTokens: 42 }          ← 스트리밍 완료
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 인메모리 브로커: /topic(브로드캐스트), /queue(개인)
        config.enableSimpleBroker("/topic", "/queue");
        // 클라이언트 → 서버 메시지 prefix
        config.setApplicationDestinationPrefixes("/app");
        // convertAndSendToUser() 의 목적지 prefix
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // STOMP CONNECT 시 JWT 검증
        registration.interceptors(webSocketAuthInterceptor);
    }
}
