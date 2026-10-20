package com.ieum.api.deployment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import com.ieum.api.workflow.queue.ExecutionJobQueueBootstrap;
import org.quartz.SchedulerException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentLifecycle {
    private final DeploymentControl control;
    private final Scheduler scheduler;
    private final ExecutionJobQueueBootstrap bootstrap;

    @Scheduled(fixedDelay = 20000, initialDelay = 1000)
    public void reconcile() throws SchedulerException {
        control.heartbeat();
        if (!bootstrap.isDraining() && control.distributedReady() && scheduler.isInStandbyMode()) {
            scheduler.start();
            log.info("Distributed Quartz scheduler enabled");
        }
    }
}
