package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.*;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import org.junit.jupiter.api.Test;

class DeploymentWorkTrackerTest {
    @Test
    void closingTransportDoesNotLoseBackgroundWorkAndLeaseClosesExactlyOnce() {
        var connections = new DeploymentConnections(new ExecutionEventPublisher());
        var tracker = new DeploymentWorkTracker(connections);
        var lease = tracker.tryBegin();
        assertThat(tracker.activeCount()).isEqualTo(1);
        connections.retire();
        assertThat(tracker.activeCount()).isEqualTo(1);
        assertThat(tracker.tryBegin()).isNull();
        lease.close();
        lease.close();
        assertThat(tracker.activeCount()).isZero();
        connections.resume();
        try (var resumed = tracker.tryBegin()) {
            assertThat(resumed).isNotNull();
            assertThat(tracker.activeCount()).isEqualTo(1);
        }
        assertThat(tracker.activeCount()).isZero();
    }
}
