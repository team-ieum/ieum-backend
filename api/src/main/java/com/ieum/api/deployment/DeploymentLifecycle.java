package com.ieum.api.deployment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "deployment-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void startHeartbeat() {
        heartbeatExecutor.scheduleWithFixedDelay(this::refreshHeartbeat, 0, 20, TimeUnit.SECONDS);
    }

    void refreshHeartbeat() {
        try { control.heartbeat(); }
        catch (Exception e) { log.error("Deployment heartbeat refresh failed", e); }
    }

    @PreDestroy
    void stopHeartbeat() { heartbeatExecutor.shutdownNow(); }

    @Scheduled(fixedDelay = 20000, initialDelay = 1000)
    public void reconcile() throws SchedulerException {
        // Redis failure must not prevent the independent JDBC Quartz lifecycle from reconciling.
        synchronized (control) {
            if (!control.isDraining() && !bootstrap.isDraining() && control.distributedReady()
                    && scheduler.isInStandbyMode()) {
                scheduler.start();
                log.info("Distributed Quartz scheduler enabled");
            }
        }
    }
}
