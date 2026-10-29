package com.ieum.api.chat.service;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 사용자당 동시 채팅 턴 1개 (IEUM-BE-106) — Redis {@code SET chat:inflight:{userId} 1 NX EX 660}.
 *
 * <p>WS 채팅엔 빈도·동시성 제한이 없고 agent가 부르는 노드 테스트는 BE 요청 스레드에서 최대 180초 돈다 — 연타로
 * BE 스레드가 묶이고, 여러 탭의 동시 확인으로 쓰기 테스트가 두 번 돌 수 있다. 해제는 스트림 {@code doFinally}
 * (위임 토큰 폐기와 같은 자리, {@code WebSocketChatHandler}).
 */
@Component
@RequiredArgsConstructor
public class ChatTurnLock {

    /** 설정 단계 + 스트림 570초 + DONE 저장보다 길게 — 해제가 유실돼도(프로세스 종료) 11분 뒤 풀린다. */
    static final Duration TTL = Duration.ofSeconds(660);

    private final StringRedisTemplate redisTemplate;

    /** 잡았으면 true. 이미 진행 중인 턴이 있으면 false. */
    public boolean tryAcquire(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key(userId), "1", TTL));
    }

    // ponytail: 소유자 확인 없는 DEL. 보유 시간 = 설정 단계(Google 토큰 갱신 최대 40초) + 스트림 570초 + DONE 저장 < TTL 660초라
    // 정상 턴은 TTL 전에 풀린다. 저장이 50초 넘게 걸리는 예외 상황에서만 한 턴이 겹칠 수 있다(빈도 제한일 뿐 데이터 손상 없음).
    // 문제가 되면 값에 턴 id를 넣고 Lua로 비교 삭제한다.
    public void release(UUID userId) {
        redisTemplate.delete(key(userId));
    }

    static String key(UUID userId) {
        return "chat:inflight:" + userId;
    }
}
