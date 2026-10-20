package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.test.StepVerifier;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

class DeploymentConnectionsTest {
    @Test
    void retirementClosesSseAndBlocksNewApiUntilResume() throws Exception {
        var publisher = new ExecutionEventPublisher();
        var connections = new DeploymentConnections(publisher);
        var filter = new DeploymentRequests(connections);
        var request = new MockHttpServletRequest("POST", "/api/v1/workflows/run");
        var response = new MockHttpServletResponse();
        StepVerifier.create(publisher.subscribe(UUID.randomUUID()))
            .then(connections::retire).verifyComplete();
        filter.doFilter(request, response, (req, res) -> fail("Retired API must reject new requests"));
        assertThat(response.getStatus()).isEqualTo(503);
        connections.resume();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/workflows"),
            new MockHttpServletResponse(), (req, res) -> assertThat(filter.activeCount()).isEqualTo(1));
        assertThat(filter.activeCount()).isZero();
    }

    @Test
    void asynchronousApiIsCountedUntilCompletion() throws Exception {
        var connections = new DeploymentConnections(new ExecutionEventPublisher());
        var filter = new DeploymentRequests(connections);
        var request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        request.setAsyncSupported(true);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync());
        assertThat(filter.activeCount()).isEqualTo(1);
        request.getAsyncContext().complete();
        assertThat(filter.activeCount()).isZero();
    }

    @Test
    void retirementClosesActualTransportWithServiceRestartStatus() throws Exception {
        var connections = new DeploymentConnections(new ExecutionEventPublisher());
        var registration = mock(WebSocketTransportRegistration.class);
        connections.configureWebSocketTransport(registration);
        var factory = ArgumentCaptor.forClass(WebSocketHandlerDecoratorFactory.class);
        verify(registration).addDecoratorFactory(factory.capture());
        var handler = factory.getValue().decorate(mock(WebSocketHandler.class));
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("authenticated-session");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> { when(session.isOpen()).thenReturn(false); return null; })
            .when(session).close(CloseStatus.SERVICE_RESTARTED);
        handler.afterConnectionEstablished(session);
        assertThat(connections.sessionCount()).isEqualTo(1);
        connections.retire();
        verify(session).close(CloseStatus.SERVICE_RESTARTED);
        assertThat(connections.sessionCount()).isZero();
    }
}
