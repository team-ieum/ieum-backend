package com.ieum.api.webhook.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * webhook 트리거 노드 테스트의 "수신 대기" 표식. Redis {@code webhook:listen:{workflowId}} = 대기 중인 nodeId,
 * TTL 5분. 워크플로우당 하나라 새 테스트 요청이 이전 대기를 덮는다.
 *
 * <p>{@link #consume}은 GETDEL 한 번이다 — 조회와 삭제를 나누면 동시에 들어온 두 요청이 둘 다 대기 중으로
 * 보고 샘플을 두 번 만든다. 소비 쪽 Redis 장애는 삼킨다(웹훅 자체의 실행을 막지 않는다 —
 * {@code DefaultIdempotencyStore}와 같은 방향). 시작 쪽은 삼키지 않는다 — 대기 중이 아닌데 LISTENING이라고
 * 답하면 사용자가 허공에 요청을 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookListenStore {

    static final String KEY_PREFIX = "webhook:listen:";
    public static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    /** 대기를 시작(또는 갱신)하고 만료 시각을 돌려준다. */
    public LocalDateTime start(UUID workflowId, String nodeId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + workflowId, nodeId, TTL);
        return LocalDateTime.now().plus(TTL);
    }

    /** 대기 중인 nodeId를 꺼내며 키를 지운다. 대기 중이 아니거나 이미 다른 요청이 가져갔으면 empty. */
    public Optional<String> consume(UUID workflowId) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + workflowId));
        } catch (Exception e) {
            log.warn("[WebhookListenStore] Redis 장애 — 대기 샘플 없이 진행. workflowId: {}", workflowId, e);
            return Optional.empty();
        }
    }
}
