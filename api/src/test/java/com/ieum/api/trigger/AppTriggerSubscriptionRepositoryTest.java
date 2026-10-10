package com.ieum.api.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 H2 스키마에서 JSON 칼럼 왕복·클레임 리스·SKIP LOCKED·삭제를 본다. 컨텍스트 구성은
 * {@code NodeTestSampleRepositoryTest}와 같다. 잠금 테스트 때문에 클래스에 @Transactional을 달지 않고 매번 비운다.
 */
@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class AppTriggerSubscriptionRepositoryTest {

    @TestConfiguration
    static class NoRedisConfig {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
            org.mockito.BDDMockito.given(factory.getConnection())
                .willThrow(new RedisConnectionFailureException("테스트 환경엔 Redis가 없다"));
            return factory;
        }
    }

    @Autowired private AppTriggerSubscriptionRepository repository;
    @Autowired private AppTriggerSubscriptionService service;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private AppTriggerSubscription gmail(LocalDateTime createdAt) {
        return repository.save(AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of("query", "from:x"), createdAt));
    }

    @Test
    @DisplayName("JSON 칼럼 왕복 — config·cursor가 Map으로 돌아온다")
    void jsonRoundTrip() {
        AppTriggerSubscription saved = gmail(LocalDateTime.now());
        service.recordPoll(saved.getId(), saved.getConfig(), Map.of("afterSec", 1_790_000_000L, "seenIds", List.of("m1")),
            null, LocalDateTime.now().plusMinutes(5));

        AppTriggerSubscription read = repository.findById(saved.getId()).orElseThrow();

        assertThat(read.getConfig()).containsEntry("query", "from:x");
        assertThat(((Number) read.getCursor().get("afterSec")).longValue()).isEqualTo(1_790_000_000L);
        assertThat(read.getCursor().get("seenIds")).isEqualTo(List.of("m1"));
        assertThat(read.getLastPolledAt()).isNotNull();
    }

    @Test
    @DisplayName("recordPoll — 클레임 뒤 query가 바뀌었으면(리셋) 옛 커서로 덮지 않고 다음 폴링·오류만 기록")
    void recordPollSkipsCursorWhenReconfiguredMeanwhile() {
        // H2 timestamp는 마이크로초로 반올림 저장한다(Linux JDK now()는 나노초) — 왕복 후 정확 비교하려면 잘라 둔다
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        gmail(now.minusMinutes(10));
        AppTriggerSubscription claimed =
            service.claimDue(TriggerType.GMAIL_NEW_EMAIL, now, 50, Duration.ofMinutes(5)).get(0);
        transactionTemplate.executeWithoutResult(tx -> repository.findById(claimed.getId()).orElseThrow()
            .reconfigure(TriggerType.GMAIL_NEW_EMAIL, claimed.getUserId(), null, Map.of("query", "from:y"), now));
        Map<String, Object> reset = repository.findById(claimed.getId()).orElseThrow().getCursor();
        LocalDateTime next = now.plusMinutes(5);

        service.recordPoll(claimed.getId(), claimed.getConfig(),
            Map.of("afterSec", 1_790_000_000L, "seenIds", List.of("old")), "ERR", next);

        AppTriggerSubscription read = repository.findById(claimed.getId()).orElseThrow();
        assertThat(read.getCursor()).isEqualTo(reset);
        assertThat(read.getLastError()).isEqualTo("ERR");
        assertThat(read.getNextPollAt()).isEqualTo(next);
    }

    @Test
    @DisplayName("claimDue — 기한 지난 해당 타입 행만, 집은 행은 리스되어 다시 안 집힌다")
    void claimLeasesRows() {
        LocalDateTime now = LocalDateTime.now();
        gmail(now.minusMinutes(10));
        gmail(now.minusMinutes(1));
        gmail(now.plusMinutes(3));                      // 아직 아님
        repository.save(AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GITHUB_NEW_ISSUE, "1", Map.of("repoId", 1L), now.minusMinutes(10)));  // 폴링 없음

        List<AppTriggerSubscription> first = service.claimDue(TriggerType.GMAIL_NEW_EMAIL, now, 50, Duration.ofMinutes(5));
        List<AppTriggerSubscription> second = service.claimDue(TriggerType.GMAIL_NEW_EMAIL, now, 50, Duration.ofMinutes(5));

        assertThat(first).hasSize(2);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("claimDue — limit만큼만, 오래 기다린 행부터")
    void claimRespectsLimit() {
        LocalDateTime now = LocalDateTime.now();
        AppTriggerSubscription oldest = gmail(now.minusMinutes(30));
        gmail(now.minusMinutes(20));
        gmail(now.minusMinutes(10));

        List<AppTriggerSubscription> claimed = service.claimDue(TriggerType.GMAIL_NEW_EMAIL, now, 2, Duration.ofMinutes(5));

        assertThat(claimed).hasSize(2).extracting(AppTriggerSubscription::getId).contains(oldest.getId());
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 행은 기다리지 않고 건너뛴다(SKIP LOCKED)")
    void lockedRowsAreSkippedByConcurrentClaim() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        gmail(now.minusMinutes(10));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(tx -> {
            repository.lockDue(TriggerType.GMAIL_NEW_EMAIL.name(), now, 50);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // 평문 FOR UPDATE였다면 여기서 막혀 락 타임아웃 예외로 끝나거나, 타임아웃이 길면 holder가 놓은 뒤 행을 집는다 —
        // 어느 쪽이든 아래 단정이 깨지므로 시간 측정에 기대지 않는다.
        List<AppTriggerSubscription> claimed;
        try {
            claimed = service.claimDue(TriggerType.GMAIL_NEW_EMAIL, now, 50, Duration.ofMinutes(5));
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);

        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("findByResource·deleteByWorkflowId")
    void lookupAndDelete() {
        UUID workflowId = UUID.randomUUID();
        repository.save(AppTriggerSubscription.create(workflowId, "t1", UUID.randomUUID(),
            TriggerType.GITHUB_NEW_ISSUE, "42", Map.of("repoId", 42L), LocalDateTime.now()));
        repository.save(AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GITHUB_NEW_ISSUE, "43", Map.of("repoId", 43L), LocalDateTime.now()));

        assertThat(service.findByResource(TriggerType.GITHUB_NEW_ISSUE, "42"))
            .extracting(AppTriggerSubscription::getWorkflowId).containsExactly(workflowId);

        service.deleteByWorkflowId(workflowId);

        assertThat(service.findByResource(TriggerType.GITHUB_NEW_ISSUE, "42")).isEmpty();
        assertThat(service.findByResource(TriggerType.GITHUB_NEW_ISSUE, "43")).hasSize(1);
    }
}
