package com.ieum.api.deployment;

import static org.mockito.Mockito.*;
import com.ieum.api.workflow.queue.ExecutionJobQueueBootstrap;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

class DeploymentLifecycleTest {
    @Test
    void redisHeartbeatFailureDoesNotPreventJdbcQuartzReconciliation() throws Exception {
        var control = mock(DeploymentControl.class);
        var scheduler = mock(Scheduler.class);
        var bootstrap = mock(ExecutionJobQueueBootstrap.class);
        doThrow(new IllegalStateException("Redis offline")).when(control).heartbeat();
        when(control.distributedReady()).thenReturn(true);
        when(scheduler.isInStandbyMode()).thenReturn(true);
        var lifecycle = new DeploymentLifecycle(control, scheduler, bootstrap);
        lifecycle.refreshHeartbeat();
        lifecycle.reconcile();
        verify(scheduler).start();
        lifecycle.stopHeartbeat();
    }

    @Test
    void drainedInstanceDoesNotRestartQuartz() throws Exception {
        var control = mock(DeploymentControl.class);
        var scheduler = mock(Scheduler.class);
        when(control.isDraining()).thenReturn(true);
        var lifecycle = new DeploymentLifecycle(control, scheduler, mock(ExecutionJobQueueBootstrap.class));
        lifecycle.reconcile();
        verify(scheduler, never()).start();
        lifecycle.stopHeartbeat();
    }
}
