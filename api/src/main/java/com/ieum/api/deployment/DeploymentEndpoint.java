package com.ieum.api.deployment;

import com.ieum.api.workflow.queue.ExecutionJobQueueBootstrap;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.Executor;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.messaging.simp.user.SimpUserRegistry;

@Component
@Endpoint(id = "deployment")
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class DeploymentEndpoint {
    private final DeploymentControl control;
    private final ExecutionJobQueueBootstrap bootstrap;
    private final Scheduler scheduler;
    private final ThreadPoolTaskExecutor executor;
    private final ExecutionEventPublisher events;
    private final SimpUserRegistry users;
    private final Path tokenPath;
    private final String instance;

    public DeploymentEndpoint(DeploymentControl control, ExecutionJobQueueBootstrap bootstrap,
            Scheduler scheduler, @Qualifier("workflowExecutor") Executor executor,
            ExecutionEventPublisher events, SimpUserRegistry users,
            @Value("${ieum.deployment.control-dir:/deployment}") String directory,
            @Value("${ieum.deployment.instance}") String instance) {
        this.control = control; this.bootstrap = bootstrap; this.scheduler = scheduler;
        this.executor = (ThreadPoolTaskExecutor) executor; this.events = events; this.users = users;
        this.tokenPath = Path.of(directory, "token"); this.instance = instance;
    }

    @ReadOperation
    public Map<String, Object> status() throws Exception {
        return Map.of("instance", instance, "distributedReady", control.distributedReady(),
            "draining", bootstrap.isDraining(), "activeJobs", executor.getActiveCount(),
            "queuedJobs", executor.getThreadPoolExecutor().getQueue().size(),
            "sseSubscribers", events.subscriberCount(), "websocketUsers", users.getUserCount(),
            "clusteredQuartz", scheduler.getMetaData().isJobStoreClustered(),
            "schedulerStandby", scheduler.isInStandbyMode());
    }

    @WriteOperation
    public Map<String, Object> control(String action, String token) throws Exception {
        String expected = Files.readString(tokenPath).strip();
        if (expected.isEmpty() || token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if ("drain".equals(action)) {
            bootstrap.stopConsuming();
            scheduler.standby();
        } else if ("resume".equals(action)) {
            bootstrap.resumeConsuming();
            if (control.distributedReady()) scheduler.start();
        } else { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        return status();
    }
}
