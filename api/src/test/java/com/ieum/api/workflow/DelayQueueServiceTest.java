package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.workflowcore.engine.executor.DelayFailure;
import com.ieum.workflowcore.repository.DelayQueueRepository;
import com.ieum.workflowcore.service.DelayQueueService;
import com.ieum.workflowcore.service.DelayQueueService.Slot;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** 큐 행 잠금·한도를 실제 테스트 DB로 확인한다. 동시성 테스트가 있어 클래스 트랜잭션 없이 매 테스트 뒤 비운다. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class DelayQueueServiceTest {
    private static final Instant T = Instant.parse("2026-11-09T01:30:00Z");
    private static final Duration MIN = Duration.ofMinutes(1);

    @Autowired private DelayQueueService service;
    @Autowired private DelayQueueRepository repository;

    private final UUID u = UUID.randomUUID();

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 빈_큐는_지금_바로() {
        assertThat(service.reserve(u, "slack", Duration.ofMinutes(10), T)).isEqualTo(new Slot(T, 0));
    }

    @Test
    void 연달아_넣으면_간격만큼_밀린다() {
        Duration ten = Duration.ofMinutes(10);
        assertThat(service.reserve(u, "slack", ten, T)).isEqualTo(new Slot(T, 0));
        assertThat(service.reserve(u, "slack", ten, T.plus(MIN)))
            .isEqualTo(new Slot(T.plus(Duration.ofMinutes(10)), 0));
        assertThat(service.reserve(u, "slack", ten, T.plus(Duration.ofMinutes(2))))
            .isEqualTo(new Slot(T.plus(Duration.ofMinutes(20)), 1));
    }

    @Test
    void 사용자가_다르면_큐도_다르다() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        assertThat(service.reserve(u1, "slack", Duration.ofMinutes(10), T)).isEqualTo(new Slot(T, 0));
        assertThat(service.reserve(u2, "slack", Duration.ofMinutes(10), T)).isEqualTo(new Slot(T, 0));
    }

    @Test
    void _100개_차면_실패하고_슬롯을_쓰지_않는다() {
        Slot last = null;
        for (int i = 0; i < 101; i++) last = service.reserve(u, "slack", MIN, T);
        assertThat(last.position()).isEqualTo(99);

        // 큐가 찬 상태라 peek도 같은 한도에 걸린다 — 결과(실패 문구)와 저장된 마지막 시각이 시도 전후로 같아야 한다.
        String before = peekResult();
        var lastBefore = repository.findByUserIdAndName(u, "slack").orElseThrow().getLastReleaseAt();
        assertThatThrownBy(() -> service.reserve(u, "slack", MIN, T))
            .isInstanceOf(DelayFailure.class)
            .hasMessage("큐 'slack'에 기다리는 실행이 100개를 넘었어요");
        assertThat(peekResult()).isEqualTo(before);
        assertThat(repository.findByUserIdAndName(u, "slack").orElseThrow().getLastReleaseAt())
            .isEqualTo(lastBefore);
    }

    private String peekResult() {
        try {
            return service.peek(u, "slack", MIN, T).toString();
        } catch (DelayFailure f) {
            return f.getMessage();
        }
    }

    @Test
    void _30일_넘는_차례는_실패() {
        Duration day = Duration.ofDays(1);
        Slot last = null;
        for (int i = 0; i < 31; i++) last = service.reserve(u, "slack", day, T);
        assertThat(last.releaseAt()).isEqualTo(T.plus(Duration.ofDays(30)));

        assertThatThrownBy(() -> service.reserve(u, "slack", day, T))
            .isInstanceOf(DelayFailure.class)
            .hasMessage("큐 'slack'의 차례가 30일 뒤를 넘어요");
    }

    @Test
    void peek은_슬롯을_쓰지_않는다() {
        Duration ten = Duration.ofMinutes(10);
        service.reserve(u, "slack", ten, T);
        Slot first = service.peek(u, "slack", ten, T);
        Slot second = service.peek(u, "slack", ten, T);
        assertThat(second).isEqualTo(first);
        assertThat(service.reserve(u, "slack", ten, T)).isEqualTo(first);
    }

    @Test
    void 동시_예약도_슬롯이_겹치지_않는다() throws Exception {
        // 행 생성 경합이 아니라 행 잠금을 보는 테스트다 — H2는 같은 키 동시 INSERT를 PG와 다르게 다룬다.
        service.reserve(u, "slack", MIN, T);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Slot>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Slot> task = () -> {
                    start.await();
                    return service.reserve(u, "slack", MIN, T);
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
