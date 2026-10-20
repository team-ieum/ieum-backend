package com.ieum.api.deployment;

import com.ieum.api.workflow.queue.ExecutionJobQueueBootstrap;
import com.ieum.api.workflow.queue.ExecutionJobWorker;
import com.ieum.api.workflow.WorkflowExecutionRunner;
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
    private final ExecutionJobWorker worker;
    private final WorkflowExecutionRunner runner;
    private final DeploymentConnections connections;
    private final DeploymentRequests requests;
    private final DeploymentWorkTracker background;

    public DeploymentEndpoint(DeploymentControl control, ExecutionJobQueueBootstrap bootstrap,
            Scheduler scheduler, @Qualifier("workflowExecutor") Executor executor,
            ExecutionEventPublisher events, SimpUserRegistry users,
            ExecutionJobWorker worker, WorkflowExecutionRunner runner,
            DeploymentConnections connections, DeploymentRequests requests, DeploymentWorkTracker background,
            @Value("${ieum.deployment.control-dir:/deployment}") String directory,
            @Value("${ieum.deployment.instance}") String instance) {
        this.control = control; this.bootstrap = bootstrap; this.scheduler = scheduler;
        this.executor = (ThreadPoolTaskExecutor) executor; this.events = events; this.users = users;
        this.tokenPath = Path.of(directory, "token"); this.instance = instance;
        this.worker = worker; this.runner = runner; this.connections = connections; this.requests = requests;
        this.background = background;
    }

    @ReadOperation
    public Map<String, Object> status() throws Exception {
        int queued = executor.getThreadPoolExecutor().getQueue().size();
        // These sets overlap, so max is intentional: a conservative nonzero signal, not a billing count.
        int executions = Math.max(worker.activeCount(), Math.max(runner.activeCount(),
            executor.getActiveCount() + queued));
        int activeJobs = executions + scheduler.getCurrentlyExecutingJobs().size() + requests.activeCount()
            + background.activeCount();
        return Map.ofEntries(Map.entry("protocolVersion", 2), Map.entry("instance", instance),
            Map.entry("consumerName", control.consumerName()),
            Map.entry("distributedReady", control.distributedReady()),
            Map.entry("draining", control.isDraining() || bootstrap.isDraining()),
            Map.entry("retired", connections.isRetired()), Map.entry("activeJobs", activeJobs),
            Map.entry("activeRequests", requests.activeCount()), Map.entry("queuedJobs", queued),
            Map.entry("activeChatJobs", background.activeCount()),
            Map.entry("sseSubscribers", events.subscriberCount()),
            Map.entry("websocketUsers", Math.max(users.getUserCount(), connections.sessionCount())),
            Map.entry("clusteredQuartz", scheduler.getMetaData().isJobStoreClustered()),
            Map.entry("schedulerStandby", scheduler.isInStandbyMode()));
    }

    @WriteOperation
    public Map<String, Object> control(String action, String token) throws Exception {
        String expected = Files.readString(tokenPath).strip();
        if (expected.isEmpty() || token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        synchronized (control) {
            if ("drain".equals(action)) {
                control.drain();
                bootstrap.stopConsuming();
                scheduler.standby();
            } else if ("retire".equals(action)) {
                if (!control.isDraining() || !bootstrap.isDraining()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Drain before retiring connections");
                }
                connections.retire();
            } else if ("resume".equals(action)) {
                connections.resume();
                control.resume();
                try {
                    bootstrap.resumeConsuming();
                    if (control.distributedReady()) scheduler.start();
                } catch (Exception failure) {
                    control.drain();
                    try { bootstrap.stopConsuming(); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    try { scheduler.standby(); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        return status();
    }
}
