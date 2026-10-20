package com.ieum.api.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.event.ExecutionEventRelay;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class RedisExecutionEventRelay implements ExecutionEventRelay, MessageListener {
    public static final String CHANNEL = "ieum:execution:events:v1";
    public record Envelope(String origin, UUID executionId, ExecutionEvent event, boolean complete) {}
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ObjectProvider<ExecutionEventPublisher> publisher;
    private final String origin;

    public RedisExecutionEventRelay(StringRedisTemplate redis, ObjectMapper mapper,
            ObjectProvider<ExecutionEventPublisher> publisher,
            @Value("${ieum.deployment.instance}") String instance) {
        this.redis = redis;
        this.mapper = mapper;
        this.publisher = publisher;
        this.origin = instance + ":" + UUID.randomUUID();
    }

    public void publish(UUID executionId, ExecutionEvent event) {
        send(new Envelope(origin, executionId, event, false));
    }

    public void complete(UUID executionId) {
        send(new Envelope(origin, executionId, null, true));
    }

    private void send(Envelope message) {
        try { redis.convertAndSend(CHANNEL, mapper.writeValueAsString(message)); }
        catch (Exception e) {
            // Local delivery continues; reconnecting clients recover their DB snapshot.
            log.error("Cross-instance execution event delivery failed for {}", message.executionId(), e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Envelope envelope = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), Envelope.class);
            if (origin.equals(envelope.origin())) return;
            if (envelope.complete()) publisher.getObject().completeLocal(envelope.executionId());
            else publisher.getObject().acceptRemote(envelope.executionId(), envelope.event());
        } catch (Exception e) { log.error("Invalid cross-instance execution event", e); }
    }
}
