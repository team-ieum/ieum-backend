package com.ieum.workflowcore.engine.event;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Local live stream hub, optionally relayed between deployment processes.
 * Terminal tombstones close the snapshot/subscribe race. Idle channels are bounded and expired;
 * a completion before subscribe must never create a fresh, permanently open stream.
 */
@Slf4j
@Component
public class ExecutionEventPublisher {
    private static final Duration STREAM_LIFETIME = Duration.ofMinutes(10);
    private static final long RETENTION_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int MAX_IDLE_CHANNELS = 4096;
    @Autowired(required = false)
    private ExecutionEventRelay relay;
    private final Map<UUID, Channel> channels = new HashMap<>();
    private final Map<UUID, Long> completed = new HashMap<>();
    private boolean retired;
    private long lastPruned;

    private record Channel(Sinks.Many<ExecutionEvent> sink, long createdAt) {}

    public void publish(UUID executionId, ExecutionEvent event) {
        synchronized (this) {
            prune();
            if (!completed.containsKey(executionId)) emit(channel(executionId), event, executionId);
            if (terminal(event)) completeLocal(executionId);
        }
        if (relay != null) relay.publish(executionId, event);
    }

    /** Capture the channel before the caller loads its DB snapshot, retaining a bounded event tail. */
    public synchronized Flux<ExecutionEvent> subscribe(UUID executionId) {
        prune();
        if (retired || completed.containsKey(executionId)) return Flux.empty();
        Channel channel = channel(executionId);
        return channel.sink().asFlux().take(STREAM_LIFETIME)
            .doFinally(signal -> cleanUpIfNoSubscribers(executionId));
    }

    public void complete(UUID executionId) {
        completeLocal(executionId);
        if (relay != null) relay.complete(executionId);
    }

    /** Remote ordinary events do not retain runs which have no local channel. */
    public synchronized void acceptRemote(UUID executionId, ExecutionEvent event) {
        if (event == null) return;
        Channel channel = channels.get(executionId);
        if (channel != null) emit(channel, event, executionId);
        // The terminal event itself closes the stream even if its separate complete message is lost.
        if (terminal(event)) completeLocal(executionId);
    }

    public synchronized void completeLocal(UUID executionId) {
        prune();
        completed.put(executionId, System.currentTimeMillis());
        Channel channel = channels.remove(executionId);
        if (channel != null) channel.sink().tryEmitComplete();
    }

    /** A zero-subscriber channel may already have been returned before snapshot loading.
     * Keep it for a bounded grace instead of detaching a concurrent, not-yet-subscribed Flux.
     */
    public synchronized void cleanUpIfNoSubscribers(UUID executionId) { prune(); }

    public synchronized int subscriberCount() {
        return channels.values().stream().mapToInt(channel -> channel.sink().currentSubscriberCount()).sum();
    }

    public synchronized Set<UUID> observedExecutionIds() {
        prune();
        return channels.entrySet().stream()
            .filter(entry -> entry.getValue().sink().currentSubscriberCount() > 0)
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Deployment retirement closes transports; it does not mark workflow executions terminal. */
    public synchronized void retireLocal() {
        retired = true;
        var retiring = channels.values().stream().toList();
        channels.clear();
        retiring.forEach(channel -> channel.sink().tryEmitComplete());
    }

    public synchronized void resumeLocal() { retired = false; }

    private Channel channel(UUID executionId) {
        return channels.computeIfAbsent(executionId, id -> new Channel(
            Sinks.many().replay().limit(256), System.currentTimeMillis()));
    }

    private void emit(Channel channel, ExecutionEvent event, UUID id) {
        Sinks.EmitResult result = channel.sink().tryEmitNext(event);
        if (result.isFailure()) log.warn("Execution event delivery failed: id={}, result={}", id, result);
    }

    private boolean terminal(ExecutionEvent event) {
        return event != null && event.type() == ExecutionEventType.EXECUTION_COMPLETED;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        if (now - lastPruned < 1000 && channels.size() <= MAX_IDLE_CHANNELS
                && completed.size() <= MAX_IDLE_CHANNELS) return;
        lastPruned = now;
        long cutoff = now - RETENTION_MILLIS;
        completed.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        channels.entrySet().removeIf(entry -> entry.getValue().sink().currentSubscriberCount() == 0
            && entry.getValue().createdAt() < cutoff);
        while (channels.size() > MAX_IDLE_CHANNELS) {
            var idle = channels.entrySet().stream()
                .filter(entry -> entry.getValue().sink().currentSubscriberCount() == 0)
                .min(java.util.Comparator.comparingLong(entry -> entry.getValue().createdAt()));
            if (idle.isEmpty()) break;
            Channel removed = channels.remove(idle.get().getKey());
            removed.sink().tryEmitComplete();
        }
        while (completed.size() > MAX_IDLE_CHANNELS) {
            completed.remove(completed.entrySet().stream()
                .min(Map.Entry.comparingByValue()).orElseThrow().getKey());
        }
    }
}
