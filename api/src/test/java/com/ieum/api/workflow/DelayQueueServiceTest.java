package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.DelayFailure;
import com.ieum.workflowcore.repository.DelayQueueRepository;
import com.ieum.workflowcore.repository.DelayQueueSlotRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import com.ieum.workflowcore.service.DelayQueueService;
import com.ieum.workflowcore.service.DelayQueueService.Slot;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 큐 = 실행별 슬롯 행. 유효 슬롯(release_at &gt; now이고 실행이 RUNNING·WAITING_DELAY·WAITING_APPROVAL)만 센다.
 * 동시성 테스트가 있어 클래스 트랜잭션 없이 매 테스트 뒤 만든 행만 지운다.
 */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class DelayQueueServiceTest {
    private static final Instant T = Instant.parse("2026-11-09T01:30:00Z");
    private static final Duration MIN = Duration.ofMinutes(1);

    @Autowired private DelayQueueService service;
    @Autowired private DelayQueueRepository queueRepository;
    @Autowired private DelayQueueSlotRepository slotRepository;
    @Autowired private WorkflowExecutionRepository executions;
    @Autowired private WorkflowRepository workflows;
    @Autowired private WorkflowVersionRepository versions;

    private final UUID u = UUID.randomUUID();
    private Workflow workflow;
    private WorkflowVersion version;
    private final List<UUID> createdRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workflow = workflows.save(Workflow.builder().userId(u).name("delay queue fixture")
            .isActive(true).triggerType(TriggerType.MANUAL).build());
        version = versions.save(WorkflowVersion.builder().workflow(workflow).version(1)
            .mongoDefinitionId("3123456789abcdef01234567").build());
    }

    @AfterEach
    void clean() {
        slotRepository.deleteAll();
        queueRepository.deleteAll();
        executions.deleteAllById(createdRuns);
        versions.delete(version);
        workflows.delete(workflow);
    }

    /** 큐에 들어갈 실행 행(RUNNING). */
    private UUID run() {
        WorkflowExecution e = executions.save(WorkflowExecution.builder().workflow(workflow).workflowVersion(version)
            .startedAt(LocalDateTime.now().withNano(0)).status(ExecutionStatus.RUNNING)
            .triggerType(TriggerType.MANUAL).build());
        createdRuns.add(e.getId());
        return e.getId();
    }

    private void cancel(UUID executionId) {
        executions.finishIfNotTerminal(executionId, ExecutionStatus.FAILED, false, LocalDateTime.now());
    }

    @Test
    @DisplayName("빈 큐는 지금 바로")
    void emptyQueue_releasesNow() {
        assertThat(service.reserve(u, "slack", Duration.ofMinutes(10), T, run())).isEqualTo(new Slot(T, 0));
    }

    @Test
    @DisplayName("연달아 넣으면 간격만큼 밀린다")
    void consecutive_shiftByInterval() {
        Duration ten = Duration.ofMinutes(10);
        assertThat(service.reserve(u, "slack", ten, T, run())).isEqualTo(new Slot(T, 0));
        assertThat(service.reserve(u, "slack", ten, T.plus(MIN), run()))
            .isEqualTo(new Slot(T.plus(Duration.ofMinutes(10)), 0));
        assertThat(service.reserve(u, "slack", ten, T.plus(Duration.ofMinutes(2)), run()))
            .isEqualTo(new Slot(T.plus(Duration.ofMinutes(20)), 1));
    }

    @Test
    @DisplayName("사용자가 다르면 큐도 다르다")
    void differentUsers_differentQueues() {
        assertThat(service.reserve(UUID.randomUUID(), "slack", Duration.ofMinutes(10), T, run()))
            .isEqualTo(new Slot(T, 0));
        assertThat(service.reserve(UUID.randomUUID(), "slack", Duration.ofMinutes(10), T, run()))
            .isEqualTo(new Slot(T, 0));
    }

    @Test
    @DisplayName("1주 간격 5개를 예약한 뒤 전부 취소하면 다음 실행 차례는 지금이다(30일 초과 실패 아님)")
    void weeklyQueue_allCancelled_nextReleasesNow() {
        Duration week = Duration.ofDays(7);
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID r = run();
            runs.add(r);
            service.reserve(u, "slack", week, T, r);
        }
        runs.forEach(this::cancel);

        assertThat(service.reserve(u, "slack", week, T, run())).isEqualTo(new Slot(T, 0));
    }

    @Test
    @DisplayName("1시간 간격 50개를 전부 취소하면 다음 실행은 +0, 앞에 0개")
    void hourlyQueue_fiftyCancelled_nextIsImmediate() {
        Duration hour = Duration.ofHours(1);
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID r = run();
            runs.add(r);
            service.reserve(u, "slack", hour, T, r);
        }
        runs.forEach(this::cancel);

        assertThat(service.reserve(u, "slack", hour, T, run())).isEqualTo(new Slot(T, 0));
    }

    @Test
    @DisplayName("간격이 섞여도 position은 실제 대기 수 — 1일 간격 2개 뒤 1분 간격은 앞에 1개, 거절되지 않는다")
    void mixedIntervals_positionIsRealWaitingCount() {
        Duration day = Duration.ofDays(1);
        service.reserve(u, "slack", day, T, run());   // T(지금 바로 나감 — 대기 아님)
        service.reserve(u, "slack", day, T, run());   // T+1일(대기)

        Slot b = service.reserve(u, "slack", MIN, T, run());

        assertThat(b.position()).isEqualTo(1);
        assertThat(b.releaseAt()).isEqualTo(T.plus(day).plus(MIN));
    }

    @Test
    @DisplayName("같은 실행이 다시 예약하면(재배달) 같은 슬롯을 돌려준다 — 멱등")
    void sameExecution_reservesSameSlot() {
        UUID first = run();
        service.reserve(u, "slack", MIN, T, run());
        Slot original = service.reserve(u, "slack", MIN, T, first);

        Slot again = service.reserve(u, "slack", MIN, T.plus(Duration.ofSeconds(30)), first);

        assertThat(again.releaseAt()).isEqualTo(original.releaseAt());
        assertThat(slotRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("유효 슬롯이 100개면 거절하고 슬롯을 쓰지 않는다")
    void hundredLiveSlots_rejects() {
        Slot last = null;
        for (int i = 0; i < 101; i++) last = service.reserve(u, "slack", MIN, T, run());
        assertThat(last.position()).isEqualTo(99);

        long before = slotRepository.count();
        assertThatThrownBy(() -> service.reserve(u, "slack", MIN, T, run()))
            .isInstanceOf(DelayFailure.class)
            .hasMessage("큐 'slack'에 기다리는 실행이 100개를 넘었어요");
        assertThat(slotRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("30일 넘는 차례는 실패")
    void beyondThirtyDays_fails() {
        Duration day = Duration.ofDays(1);
        Slot last = null;
        for (int i = 0; i < 31; i++) last = service.reserve(u, "slack", day, T, run());
        assertThat(last.releaseAt()).isEqualTo(T.plus(Duration.ofDays(30)));

        assertThatThrownBy(() -> service.reserve(u, "slack", day, T, run()))
            .isInstanceOf(DelayFailure.class)
            .hasMessage("큐 'slack'의 차례가 30일 뒤를 넘어요");
    }

    @Test
    @DisplayName("peek은 슬롯을 쓰지 않는다")
    void peek_doesNotWrite() {
        Duration ten = Duration.ofMinutes(10);
        service.reserve(u, "slack", ten, T, run());
        long before = slotRepository.count();
        Slot first = service.peek(u, "slack", ten, T);
        Slot second = service.peek(u, "slack", ten, T);

        assertThat(second).isEqualTo(first);
        assertThat(slotRepository.count()).isEqualTo(before);
        assertThat(service.reserve(u, "slack", ten, T, run())).isEqualTo(first);
    }

    @Test
    @DisplayName("오래된 슬롯(차례가 최대 간격 30일보다 더 지난 것)만 지운다 — 간격 기준이 될 수 있는 슬롯은 남긴다")
    void purgeOldSlots_removesOnlyBeyondMaxInterval() {
        service.reserve(u, "old", MIN, T.minus(Duration.ofDays(31)), run());
        service.reserve(u, "recent", MIN, T.minus(Duration.ofDays(2)), run());

        service.purgeOldSlots(T);

        assertThat(slotRepository.findAll()).extracting(s -> s.getReleaseAt())
            .containsExactly(LocalDateTime.ofInstant(T.minus(Duration.ofDays(2)), java.time.ZoneId.systemDefault()));
    }

    @Test
    @DisplayName("회원 탈퇴 정리는 그 사용자의 큐·슬롯만 지운다")
    void deleteAllByUserId_removesOnlyThatUsersQueuesAndSlots() {
        UUID other = UUID.randomUUID();
        service.reserve(u, "slack", MIN, T, run());
        service.reserve(u, "mail", MIN, T, run());
        service.reserve(other, "slack", MIN, T, run());

        service.deleteAllByUserId(u);

        assertThat(queueRepository.findAll()).extracting(q -> q.getUserId()).containsExactly(other);
        assertThat(slotRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 예약도 슬롯이 겹치지 않는다")
    void concurrentReserve_doesNotOverlap() throws Exception {
        // 행 생성 경합이 아니라 행 잠금을 보는 테스트다 — H2는 같은 키 동시 INSERT를 PG와 다르게 다룬다.
        service.reserve(u, "slack", MIN, T, run());

        int threads = 8;
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < threads; i++) runs.add(run());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Slot>> futures = new ArrayList<>();
            for (UUID r : runs) {
                Callable<Slot> task = () -> {
                    start.await();
                    return service.reserve(u, "slack", MIN, T, r);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Instant> releases = new ArrayList<>();
            for (Future<Slot> f : futures) releases.add(f.get(30, TimeUnit.SECONDS).releaseAt());

            List<Instant> expected = new ArrayList<>();
            for (int i = 1; i <= threads; i++) expected.add(T.plus(Duration.ofMinutes(i)));
            assertThat(releases).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
    }
}
