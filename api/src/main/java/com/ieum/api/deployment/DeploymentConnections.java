package com.ieum.api.deployment;

import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/** Explicit connection retirement after background jobs drain and the connection grace expires. */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentConnections implements WebSocketMessageBrokerConfigurer {
    private final ExecutionEventPublisher events;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean retired;

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(delegate -> new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                if (retired) {
                    close(session);
                    return;
                }
                try { super.afterConnectionEstablished(session); }
                catch (Exception e) { sessions.remove(session.getId(), session); throw e; }
                // Covers retirement between the initial check and protocol establishment.
                if (retired) close(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId(), session);
                super.afterConnectionClosed(session, status);
            }
        });
    }

    public synchronized void retire() {
        retired = true;
        events.retireLocal();
        sessions.values().forEach(this::close);
    }

    public synchronized void resume() {
        events.resumeLocal();
        retired = false;
    }

    public int sessionCount() { return sessions.size(); }
    public boolean isRetired() { return retired; }

    private void close(WebSocketSession session) {
        try { session.close(CloseStatus.SERVICE_RESTARTED); }
        catch (Exception e) { log.warn("Could not retire WebSocket session {}", session.getId(), e); }
        finally { if (!session.isOpen()) sessions.remove(session.getId(), session); }
    }
}
