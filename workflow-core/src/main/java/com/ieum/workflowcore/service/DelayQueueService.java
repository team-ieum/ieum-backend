package com.ieum.workflowcore.service;

import com.ieum.workflowcore.domain.DelayQueue;
import com.ieum.workflowcore.domain.DelayQueueSlot;
import com.ieum.workflowcore.engine.executor.DelayFailure;
import com.ieum.workflowcore.repository.DelayQueueRepository;
import com.ieum.workflowcore.repository.DelayQueueSlotRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기다리기 큐(delay.queue) 차례. 같은 사용자의 같은 이름 큐끼리 실행별 슬롯 행({@code delay_queue_slots})으로 센다.
 *
 * <ul>
 *   <li>차례 = max(지금, 살아 있는 실행의 마지막 차례 + 간격). 취소·실패(FAILED)한 실행의 차례는 비운 것으로 본다 —
 *       지우는 훅 없이 세는 쪽이 {@code workflow_runs} 상태를 조인한다.</li>
 *   <li>position = 아직 나가지 않은(차례 &gt; 지금) 대기·실행 중 슬롯 수. 간격이 섞여도 정확하다.</li>
 *   <li>같은 실행이 다시 예약하면(재배달) 같은 슬롯을 돌려준다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DelayQueueService {

    public static final int MAX_WAITING = 100;
    public static final Duration MAX_AHEAD = Duration.ofDays(30);

    private final DelayQueueRepository queueRepository;
    private final DelayQueueSlotRepository slotRepository;

    public record Slot(Instant releaseAt, int position) {}

    /** 큐 행을 잠가 직렬화한 뒤 이 실행의 슬롯을 정한다. 한도를 넘으면 {@link DelayFailure}이고 슬롯을 쓰지 않는다. */
    @Transactional
    public Slot reserve(UUID userId, String name, Duration interval, Instant now, UUID executionId) {
        queueRepository.insertIfAbsent(UUID.randomUUID(), userId, name, LocalDateTime.now());
        DelayQueue queue = queueRepository.findForUpdate(userId, name).orElseThrow();
        Optional<DelayQueueSlot> existing = slotRepository.findByQueueIdAndExecutionId(queue.getId(), executionId);
        if (existing.isPresent()) {
            LocalDateTime at = existing.get().getReleaseAt();
            return new Slot(toInstant(at), (int) slotRepository.countWaiting(queue.getId(), local(now), at));
        }
        Slot slot = next(queue.getId(), name, interval, now);
        slotRepository.save(DelayQueueSlot.of(queue.getId(), executionId, local(slot.releaseAt())));
        return slot;
    }

    /** "지금 넣으면 언제"만 계산한다(노드 테스트). 슬롯을 쓰지 않는다. */
    @Transactional(readOnly = true)
    public Slot peek(UUID userId, String name, Duration interval, Instant now) {
        return queueRepository.findByUserIdAndName(userId, name)
            .map(queue -> next(queue.getId(), name, interval, now))
            .orElseGet(() -> new Slot(now, 0));
    }

    /**
     * 차례가 {@code now − 30일}보다 이른 슬롯을 1,000개씩 지운다(재개 sweeper가 부른다). 최대 간격이 30일이라 그보다 오래된
     * 슬롯은 다음 차례 계산에 영향이 없다 — 더 일찍 지우면 긴 간격 큐의 간격이 깨진다. 배치마다 따로 커밋한다.
     */
    public void purgeOldSlots(Instant now) {
        LocalDateTime cutoff = local(now.minus(MAX_AHEAD));
        while (slotRepository.deleteReleasedBefore(cutoff, PURGE_BATCH) == PURGE_BATCH) {
            // 꽉 찬 배치면 더 남았을 수 있다
        }
    }

    private static final int PURGE_BATCH = 1000;

    /** 회원 탈퇴 — 사용자의 큐·슬롯을 지운다. 호출자 트랜잭션에 합류한다. */
    @Transactional
    public void deleteAllByUserId(UUID userId) {
        slotRepository.deleteByUserId(userId);
        queueRepository.deleteByUserId(userId);
    }

    private Slot next(UUID queueId, String name, Duration interval, Instant now) {
        LocalDateTime last = slotRepository.findLastRelease(queueId);
        Instant candidate = last == null ? now : toInstant(last).plus(interval);
        Instant releaseAt = candidate.isAfter(now) ? candidate : now;
        int position = (int) slotRepository.countWaiting(queueId, local(now), local(releaseAt));
        if (position >= MAX_WAITING) {
            throw new DelayFailure("큐 '" + name + "'에 기다리는 실행이 " + MAX_WAITING + "개를 넘었어요");
        }
        if (releaseAt.isAfter(now.plus(MAX_AHEAD))) {
            throw new DelayFailure("큐 '" + name + "'의 차례가 30일 뒤를 넘어요");
        }
        return new Slot(releaseAt, position);
    }

    private static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneId.systemDefault());
    }

    private static Instant toInstant(LocalDateTime at) {
        return at.atZone(ZoneId.systemDefault()).toInstant();
    }
}
