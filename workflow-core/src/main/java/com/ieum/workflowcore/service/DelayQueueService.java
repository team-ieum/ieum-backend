package com.ieum.workflowcore.service;

import com.ieum.workflowcore.domain.DelayQueue;
import com.ieum.workflowcore.engine.executor.DelayFailure;
import com.ieum.workflowcore.repository.DelayQueueRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기다리기 큐(delay.queue) 슬롯. 같은 사용자의 같은 이름 큐끼리 "마지막 내보낼 시각 + 간격"으로 차례를 정한다.
 *
 * <p>ponytail: position은 "아직 안 나간 앞 실행 수"를 마지막 시각과 간격으로 역산한 값이라, 같은 큐에 간격이 다른
 * 실행이 섞이면 근사값이다. 취소된 실행의 슬롯도 비운 채 둔다(당겨 쓰지 않는다). 정확한 수가 필요해지면 대기 행 테이블로 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class DelayQueueService {

    public static final int MAX_WAITING = 100;
    public static final Duration MAX_AHEAD = Duration.ofDays(30);

    private final DelayQueueRepository repository;

    public record Slot(Instant releaseAt, int position) {}

    static Slot next(Instant lastRelease, Duration interval, Instant now) {
        if (lastRelease == null) return new Slot(now, 0);
        Instant candidate = lastRelease.plus(interval);
        Instant releaseAt = candidate.isAfter(now) ? candidate : now;
        int position = 0;
        if (lastRelease.isAfter(now)) {
            long ahead = Duration.between(now, lastRelease).toNanos();
            long step = interval.toNanos();
            position = (int) ((ahead + step - 1) / step);
        }
        return new Slot(releaseAt, position);
    }

    @Transactional
    public Slot reserve(UUID userId, String name, Duration interval, Instant now) {
        repository.insertIfAbsent(UUID.randomUUID(), userId, name, LocalDateTime.now());
        DelayQueue queue = repository.findForUpdate(userId, name).orElseThrow();
        Slot slot = checked(name, next(toInstant(queue.getLastReleaseAt()), interval, now), now);
        queue.release(LocalDateTime.ofInstant(slot.releaseAt(), ZoneId.systemDefault()));
        return slot;
    }

    @Transactional(readOnly = true)
    public Slot peek(UUID userId, String name, Duration interval, Instant now) {
        Instant last = repository.findByUserIdAndName(userId, name)
            .map(DelayQueue::getLastReleaseAt).map(DelayQueueService::toInstant).orElse(null);
        return checked(name, next(last, interval, now), now);
    }

    private static Slot checked(String name, Slot slot, Instant now) {
        if (slot.position() >= MAX_WAITING) {
            throw new DelayFailure("큐 '" + name + "'에 기다리는 실행이 " + MAX_WAITING + "개를 넘었어요");
        }
        if (slot.releaseAt().isAfter(now.plus(MAX_AHEAD))) {
            throw new DelayFailure("큐 '" + name + "'의 차례가 30일 뒤를 넘어요");
        }
        return slot;
    }

    private static Instant toInstant(LocalDateTime at) {
        return at == null ? null : at.atZone(ZoneId.systemDefault()).toInstant();
    }
}
