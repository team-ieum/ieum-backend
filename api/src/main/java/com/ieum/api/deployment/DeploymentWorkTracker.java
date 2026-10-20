package com.ieum.api.deployment;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Counts background chat streams independently of their WebSocket transport lifetime. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentWorkTracker {
    private final DeploymentConnections connections;
    private final AtomicInteger active = new AtomicInteger();

    public Lease tryBegin() {
        synchronized (connections) {
            if (connections.isRetired()) return null;
            active.incrementAndGet();
            return new Lease();
        }
    }

    public int activeCount() { return active.get(); }

    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override
        public void close() { if (closed.compareAndSet(false, true)) active.decrementAndGet(); }
    }
}
