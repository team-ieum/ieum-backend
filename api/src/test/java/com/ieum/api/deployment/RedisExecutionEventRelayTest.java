package com.ieum.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.engine.event.*;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

class RedisExecutionEventRelayTest {
    @Test
    void eventAndCompletionCrossInstancesWithoutEcho() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var green = new ExecutionEventPublisher();
        var blue = new ExecutionEventPublisher();
        ObjectProvider<ExecutionEventPublisher> greenProvider = mock(ObjectProvider.class);
        ObjectProvider<ExecutionEventPublisher> blueProvider = mock(ObjectProvider.class);
        when(greenProvider.getObject()).thenReturn(green);
        when(blueProvider.getObject()).thenReturn(blue);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var greenRelay = new RedisExecutionEventRelay(redis, mapper, greenProvider, "green");
        var blueRelay = new RedisExecutionEventRelay(redis, mapper, blueProvider, "blue");
        ReflectionTestUtils.setField(green, "relay", greenRelay);
        ReflectionTestUtils.setField(blue, "relay", blueRelay);
        doAnswer(invocation -> {
            Message message = mock(Message.class);
            when(message.getBody()).thenReturn(((String) invocation.getArgument(1)).getBytes(StandardCharsets.UTF_8));
            greenRelay.onMessage(message, null);
            blueRelay.onMessage(message, null);
            return 2L;
        }).when(redis).convertAndSend(anyString(), anyString());
        UUID id = UUID.randomUUID();
        var event = ExecutionEvent.executionCompleted(id, UUID.randomUUID(), ExecutionStatus.SUCCESS);
        StepVerifier.create(blue.subscribe(id))
            .then(() -> { green.publish(id, event); green.complete(id); })
            .expectNext(event).verifyComplete();
        verify(redis, times(2)).convertAndSend(anyString(), anyString());
    }
}
