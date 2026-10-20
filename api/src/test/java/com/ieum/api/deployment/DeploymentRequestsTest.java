package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.*;

import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DeploymentRequestsTest {
    private final DeploymentConnections connections = new DeploymentConnections(new ExecutionEventPublisher());
    private final DeploymentRequests filter = new DeploymentRequests(connections);

    @ParameterizedTest
    @ValueSource(strings = {"/login/oauth2/code/google", "/oauth2/authorization/google", "/api/v1/workflows", "/"})
    void ordinaryHttpIncludingOAuthIsCountedThroughFinally(String path) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(),
            (request, response) -> assertThat(filter.activeCount()).isEqualTo(1));
        assertThat(filter.activeCount()).isZero();
    }

    @Test
    void failedOAuthCallbackStillReleasesItsCount() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/login/oauth2/code/google"),
            new MockHttpServletResponse(), (request, response) -> { throw new ServletException("Token exchange failed"); }))
            .isInstanceOf(ServletException.class);
        assertThat(filter.activeCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login/oauth2/code/google", "/oauth2/authorization/google", "/api/v1/workflows", "/"})
    void retiredInstanceRejectsOrdinaryHttpIncludingOAuth(String path) throws Exception {
        connections.retire();
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response,
            (request, reply) -> fail("Retired instance must reject ordinary HTTP"));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(filter.activeCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/deployment", "/actuator/health/readiness", "/ws", "/ws/info", "/ws/001/session/websocket"})
    void managementAndWebsocketTransportBypassHttpAccountingEvenWhenRetired(String path) throws Exception {
        connections.retire();
        var called = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), (request, response) -> {
            called.set(true);
            assertThat(filter.activeCount()).isZero();
        });
        assertThat(called).isTrue();
    }

    @Test
    void executionSseUsesConnectionAccountingButStillRejectsRetiredInstance() throws Exception {
        String path = "/api/v1/workflows/workflow/executions/execution/events";
        filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(),
            (request, response) -> assertThat(filter.activeCount()).isZero());
        connections.retire();
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response,
            (request, reply) -> fail("Retired SSE must not create a new connection"));
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void exclusionsUseApplicationPathWhenContextPathIsPresent() throws Exception {
        var request = new MockHttpServletRequest("GET", "/ieum/actuator/deployment");
        request.setContextPath("/ieum");
        filter.doFilter(request, new MockHttpServletResponse(),
            (req, response) -> assertThat(filter.activeCount()).isZero());
    }

    @Test
    void filterRunsBeforeSecuritySoOAuthShortCircuitIsInsideTrackedChain() {
        assertThat(AnnotationAwareOrderComparator.INSTANCE.compare(filter, new SecurityOrderBoundary())).isNegative();
    }

    @org.springframework.core.annotation.Order(-100)
    private static final class SecurityOrderBoundary {}
}
