package com.ieum.api.deployment;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Tracks HTTP requests outside the workflow pool. SSE and WebSocket are tracked as connections. */
@Component
// OAuth callbacks can finish inside Spring Security without reaching later servlet filters.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentRequests extends OncePerRequestFilter {
    private final DeploymentConnections connections;
    private final AtomicInteger active = new AtomicInteger();

    public int activeCount() { return active.get(); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        return path.equals("/actuator") || path.startsWith("/actuator/")
            || path.equals("/ws") || path.startsWith("/ws/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean eventStream = applicationPath(request)
            .matches("/api/v1/workflows/[^/]+/executions/[^/]+/events");
        synchronized (connections) {
            // Retirement and admission are atomic: a request is either counted or rejected.
            if (connections.isRetired()) {
                response.setHeader("Retry-After", "3");
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Deployment instance retired");
                return;
            }
            if (!eventStream) active.incrementAndGet();
        }
        if (eventStream) {
            chain.doFilter(request, response);
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) active.decrementAndGet(); };
        try { chain.doFilter(request, response); }
        finally {
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        public void onComplete(AsyncEvent event) { release.run(); }
                        public void onTimeout(AsyncEvent event) { release.run(); }
                        public void onError(AsyncEvent event) { release.run(); }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    });
                } catch (IllegalStateException alreadyComplete) { release.run(); }
            } else release.run();
        }
    }

    private String applicationPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
