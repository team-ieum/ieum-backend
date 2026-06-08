package com.ieum.workflowcore.engine.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 워크플로우 실행 진행 이벤트를 executionId 단위로 발행/구독하는 in-memory 허브.
 *
 * <p>워크플로우 실행은 {@code @Async} 백그라운드 스레드에서 수행되고, SSE 구독은 별도 HTTP
 * 스레드에서 일어난다. 둘을 executionId 키의 멀티캐스트 {@link Sinks.Many}로 연결한다.
 *
 * <p><b>단일 인스턴스 전제.</b> 다중 인스턴스로 확장하면 실행 스레드와 SSE 요청이 서로 다른
 * 인스턴스에 분산될 수 있어 in-memory Sink로는 전달되지 않는다. 그 경우 Redis Pub/Sub 등으로
 * 교체해야 한다.
 *
 * <p>늦게 구독한 클라이언트가 이미 종료된 노드의 이벤트를 놓치는 문제는 구독 측에서
 * {@code WorkflowExecutionLog} 스냅샷을 먼저 흘려 보완한다(이 클래스는 라이브 스트림만 담당).
 */
@Slf4j
@Component
public class ExecutionEventPublisher {

    private final Map<UUID, Sinks.Many<ExecutionEvent>> sinks = new ConcurrentHashMap<>();

    /**
     * executionId 스트림에 이벤트를 발행한다.
     * 구독자가 아직 없으면 첫 구독 전까지 버퍼링된다.
     */
    public void publish(UUID executionId, ExecutionEvent event) {
        Sinks.Many<ExecutionEvent> sink = sinks.computeIfAbsent(executionId, k -> newSink());
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("[ExecutionEvent] 발행 실패 — executionId: {}, type: {}, result: {}",
                executionId, event.type(), result);
        }
    }

    /** executionId의 라이브 이벤트 스트림을 구독한다. */
    public Flux<ExecutionEvent> subscribe(UUID executionId) {
        return sinks.computeIfAbsent(executionId, k -> newSink()).asFlux();
    }

    /** 실행 종료 시 스트림을 완료시키고 Sink를 정리한다. */
    public void complete(UUID executionId) {
        Sinks.Many<ExecutionEvent> sink = sinks.remove(executionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private Sinks.Many<ExecutionEvent> newSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }
}
