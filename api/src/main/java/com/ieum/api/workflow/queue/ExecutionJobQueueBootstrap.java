package com.ieum.api.workflow.queue;

import static com.ieum.api.workflow.queue.ExecutionJobQueue.CONSUMER;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.GROUP;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.STREAM_KEY;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 잡 큐를 기동하고(컨슈머 그룹 생성 → 소비 시작) 고아 잡을 주기적으로 회수한다.
 * 부팅 복원이라는 점에서 {@code ScheduleJobRestorer}(Quartz)와 같은 역할이다.
 *
 * <p><b>이 큐는 단일 인스턴스 배포(stop-then-start)를 전제로 한다.</b> 컨슈머 이름이 상수라
 * 인스턴스를 구분하지 않고, SSE 허브({@code ExecutionEventPublisher})도 in-memory라 애초에
 * 다중 인스턴스가 성립하지 않는다. 롤링 배포처럼 두 인스턴스가 겹치는 순간이 생기면
 * {@link #reclaimOrphanedJobs()}가 살아 있는 인스턴스의 in-flight 잡을 회수해 이중 실행할 수 있다.
 * 스케일아웃하려면 SSE 허브 교체(Redis Pub/Sub 등)와 인스턴스별 컨슈머 이름이 먼저다.
 *
 * <p>기동 실패(Redis 장애)는 삼킨다. 소비가 안 되면 {@link ExecutionJobQueue#enqueue}가 false를
 * 돌려 기존 {@code @Async} 직접 실행으로 폴백한다 — Redis는 SPOF가 아니다. 다만 설정 오류·NPE 같은
 * 프로그래밍 오류까지 삼키면 "내구성 0"이 정상 기동으로 위장되므로, Redis 예외만 잡는다.
 */
@Slf4j
@Component
public class ExecutionJobQueueBootstrap implements ApplicationRunner {

    /**
     * 한 번에 회수할 pending 엔트리 수 상한.
     * ponytail: 단순 1회 조회. 이보다 많이 밀렸다면 로그로 알리고 다음 주기에 마저 회수한다.
     */
    private static final int RECLAIM_LIMIT = 1_000;

    private final StringRedisTemplate redisTemplate;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>>
        executionJobListenerContainer;
    private final ExecutionJobWorker executionJobWorker;
    private final ExecutionJobQueue executionJobQueue;

    /**
     * 이 시간 넘게 방치된 pending 엔트리만 회수한다. <b>0으로 두지 말 것</b> — 살아 있는 소비자의
     * in-flight 잡을 훔치지 않기 위한 유일한 안전장치다. XCLAIM의 idle 시계는 "마지막 배달 이후"라
     * 실행 중에도 계속 흐르므로, <b>워크플로우 최대 소요시간보다 넉넉히 길어야</b> 한다
     * (AI 노드 하나가 agent 타임아웃 120초까지 쓸 수 있고 노드는 여러 개다).
     * 뒤집으면 이게 곧 재시작 복구 지연 상한이기도 하다 — 죽은 프로세스의 잡은 이 시간이 지나야 회수된다.
     */
    private final Duration reclaimMinIdle;

    /** 폴링이 계속 실패할 때 스핀·로그 폭주를 막는 대기. */
    private final Duration pollErrorBackoff;

    public ExecutionJobQueueBootstrap(
        StringRedisTemplate redisTemplate,
        StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            executionJobListenerContainer,
        ExecutionJobWorker executionJobWorker,
        ExecutionJobQueue executionJobQueue,
        @Value("${ieum.workflow.queue.reclaim-min-idle:PT10M}") Duration reclaimMinIdle,
        @Value("${ieum.workflow.queue.poll-error-backoff:PT5S}") Duration pollErrorBackoff
    ) {
        this.redisTemplate = redisTemplate;
        this.executionJobListenerContainer = executionJobListenerContainer;
        this.executionJobWorker = executionJobWorker;
        this.executionJobQueue = executionJobQueue;
        this.reclaimMinIdle = reclaimMinIdle;
        this.pollErrorBackoff = pollErrorBackoff;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            createGroupIfAbsent();
            startConsuming();
            log.info("[ExecutionJobQueueBootstrap] 잡 큐 기동 완료 — stream: {}, group: {}, minIdle: {}",
                STREAM_KEY, GROUP, reclaimMinIdle);
        } catch (DataAccessException e) {
            // 소비 미기동 = healthy 플래그가 false로 남아 enqueue가 폴백을 택한다.
            log.warn("[ExecutionJobQueueBootstrap] 잡 큐 기동 실패 — 큐 없이 @Async 직접 실행으로 동작한다: {}",
                e.toString());
        }
    }

    /**
     * 죽은 프로세스가 ack하지 못하고 남긴 잡을 회수해 다시 실행한다.
     *
     * <p>부팅 1회가 아니라 주기 실행이다 — {@link #reclaimMinIdle} 때문에 재시작 직후에는 고아 잡의
     * idle이 아직 짧아 회수 대상이 아니고, 그 뒤로 다시 볼 기회가 없으면 영영 유실되기 때문이다.
     *
     * <p>회수한 잡이 이미 끝난 실행인지는 워커가 DB 상태로 판정한다
     * ({@link ExecutionJobWorker} — 종료 상태면 재실행 없이 ack).
     */
    @Scheduled(
        initialDelayString = "${ieum.workflow.queue.reclaim-interval:PT1M}",
        fixedDelayString = "${ieum.workflow.queue.reclaim-interval:PT1M}")
    void reclaimOrphanedJobs() {
        if (!executionJobListenerContainer.isRunning()) {
            return;
        }
        try {
            StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
            PendingMessages pending =
                ops.pending(STREAM_KEY, GROUP, Range.unbounded(), RECLAIM_LIMIT);
            if (pending.isEmpty()) {
                return;
            }
            if (pending.size() == RECLAIM_LIMIT) {
                log.warn("[ExecutionJobQueueBootstrap] 회수 상한({}) 도달 — 남은 pending은 다음 주기에 회수된다",
                    RECLAIM_LIMIT);
            }

            List<RecordId> ids = pending.stream().map(PendingMessage::getId).toList();
            List<MapRecord<String, String, String>> claimed = ops.claim(
                STREAM_KEY, GROUP, CONSUMER, XClaimOptions.minIdle(reclaimMinIdle).ids(ids));
            if (claimed.isEmpty()) {
                return;  // 전부 아직 in-flight — minIdle이 지켜준 경우다
            }

            log.info("[ExecutionJobQueueBootstrap] 미완료 잡 회수 — {}/{}건", claimed.size(), ids.size());
            claimed.forEach(executionJobWorker::onMessage);
        } catch (DataAccessException e) {
            log.warn("[ExecutionJobQueueBootstrap] 고아 잡 회수 실패 — 다음 주기에 재시도: {}", e.toString());
        }
    }

    private void createGroupIfAbsent() {
        try {
            // MKSTREAM 포함 — 스트림이 아직 없어도 그룹이 만들어진다.
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
        } catch (DataAccessException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void startConsuming() {
        // autoAcknowledge(false): ack는 워커가 실행 종료를 확정한 뒤 직접 한다.
        // cancelOnError(항상 false): 구독을 살려 두고 onPollError가 복구를 시도한다.
        StreamReadRequest<String> request = StreamReadRequest
            .builder(StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()))
            .consumer(Consumer.from(GROUP, CONSUMER))
            .autoAcknowledge(false)
            .cancelOnError(t -> false)
            .errorHandler(this::onPollError)
            .build();

        executionJobListenerContainer.register(request, executionJobWorker);
        executionJobListenerContainer.start();
        executionJobQueue.markConsumerHealthy();
    }

    /**
     * 폴링 실패 처리. 폴링 스레드에서 동기로 돌기 때문에 여기서 자면 재시도 루프가 그만큼 느려진다
     * (기본 동작은 백오프 없이 즉시 재시도라 CPU 스핀 + 로그 폭주를 낸다).
     *
     * <p>{@link #createGroupIfAbsent()}는 멱등이라 복구와 헬스 프로브를 겸한다 — 성공하면 Redis가
     * 살아 있고 그룹도 있다는 뜻이고, 그룹이 사라졌던(NOGROUP) 경우엔 그 자리에서 다시 만들어진다.
     * 복구될 때까지 {@link ExecutionJobQueue#enqueue}는 폴백을 택한다.
     */
    void onPollError(Throwable t) {
        executionJobQueue.markConsumerUnhealthy(t);
        sleepQuietly(pollErrorBackoff);
        try {
            createGroupIfAbsent();
            executionJobQueue.markConsumerHealthy();
        } catch (DataAccessException e) {
            log.warn("[ExecutionJobQueueBootstrap] 잡 폴링 복구 실패 — 계속 재시도한다: {}", e.toString());
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
