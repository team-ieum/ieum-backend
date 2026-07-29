package com.ieum.api.workflow.queue;

import static com.ieum.api.workflow.queue.ExecutionJobQueue.CONSUMER;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.GROUP;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.STREAM_KEY;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 잡 큐를 기동한다 — 컨슈머 그룹 생성 → 고아 잡 회수 → 소비 시작 순.
 * 부팅 복원이라는 점에서 {@code ScheduleJobRestorer}(Quartz)와 같은 역할이다.
 *
 * <p>회수를 소비 시작보다 <b>먼저</b> 하는 이유: 이전 프로세스가 죽으며 ack하지 못한 엔트리는
 * pending 목록에만 남고 {@code XREADGROUP >}로는 절대 안 나온다. 소비를 먼저 켜면
 * 신규 잡과 회수분이 섞여 순서·중복 판단이 어려워진다.
 *
 * <p>회수한 잡이 이미 끝난 실행인지는 워커가 DB 상태로 판정한다
 * ({@link ExecutionJobWorker} — 종료 상태면 재실행 없이 ack).
 *
 * <p>기동 실패(Redis 장애 등)는 삼킨다. 컨테이너가 안 뜨면 {@link ExecutionJobQueue#enqueue}가
 * false를 돌려 기존 {@code @Async} 직접 실행으로 폴백한다 — Redis는 SPOF가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionJobQueueBootstrap implements ApplicationRunner {

    /**
     * 한 번에 회수할 pending 엔트리 수 상한.
     * ponytail: 단순 1회 조회. 이보다 많이 밀렸다면 로그로 알리고, 필요해지면 페이징으로 올린다.
     */
    private static final int RECLAIM_LIMIT = 1_000;

    private final StringRedisTemplate redisTemplate;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>>
        executionJobListenerContainer;
    private final ExecutionJobWorker executionJobWorker;

    @Override
    public void run(ApplicationArguments args) {
        try {
            createGroupIfAbsent();
            reclaimOrphanedJobs();
            startConsuming();
            log.info("[ExecutionJobQueueBootstrap] 잡 큐 기동 완료 — stream: {}, group: {}",
                STREAM_KEY, GROUP);
        } catch (Exception e) {
            log.warn("[ExecutionJobQueueBootstrap] 잡 큐 기동 실패 — 큐 없이 @Async 직접 실행으로 동작한다: {}",
                e.toString());
        }
    }

    private void createGroupIfAbsent() {
        try {
            // MKSTREAM 포함 — 스트림이 아직 없어도 그룹이 만들어진다.
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void reclaimOrphanedJobs() {
        PendingMessages pending = redisTemplate.opsForStream()
            .pending(STREAM_KEY, GROUP, Range.unbounded(), RECLAIM_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() == RECLAIM_LIMIT) {
            log.warn("[ExecutionJobQueueBootstrap] 회수 상한({}) 도달 — 남은 pending은 다음 기동에 회수된다",
                RECLAIM_LIMIT);
        }

        List<RecordId> ids = pending.stream().map(PendingMessage::getId).toList();
        // minIdle 0: 이 시점엔 이 인스턴스가 유일한 소비자이고 아직 소비를 시작하지도 않았으므로
        // pending에 남은 건 전부 죽은 프로세스가 남긴 고아다.
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        List<MapRecord<String, String, String>> claimed =
            ops.claim(STREAM_KEY, GROUP, CONSUMER, XClaimOptions.minIdle(Duration.ZERO).ids(ids));

        log.info("[ExecutionJobQueueBootstrap] 미완료 잡 회수 — {}건", claimed.size());
        claimed.forEach(executionJobWorker::onMessage);
    }

    private void startConsuming() {
        // autoAcknowledge(false): ack는 워커가 실행 종료를 확정한 뒤 직접 한다.
        // cancelOnError(항상 false): Redis 일시 장애로 구독이 죽으면 컨테이너는 running인데
        // 아무도 소비하지 않는 조용한 실패가 된다. 폴링 루프를 살려 둔다.
        StreamReadRequest<String> request = StreamReadRequest
            .builder(StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()))
            .consumer(Consumer.from(GROUP, CONSUMER))
            .autoAcknowledge(false)
            .cancelOnError(t -> false)
            .errorHandler(t -> log.warn("[ExecutionJobQueueBootstrap] 잡 폴링 오류: {}", t.toString()))
            .build();

        executionJobListenerContainer.register(request, executionJobWorker);
        executionJobListenerContainer.start();
    }
}
