package com.ieum.auth.delegation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 위임 토큰 (IEUM-BE-106) — WS 채팅 한 턴 동안 agent가 사용자 대신 BE 허용 경로 5개를 부르는 불투명 토큰.
 *
 * <p>원문은 32바이트 {@link SecureRandom} → base64url(패딩 없음)이고 BE→agent {@code X-Delegation-Token}으로만 나간다.
 * Redis에는 원문이 아니라 {@code delegation:{sha256 hex}} 키에 {@code {userId, role, workflowId}} JSON을 TTL 600초로 둔다.
 * {@code @RedisHash}는 쓰지 않는다 — keyspace events가 꺼져 있어 만료된 id가 인덱스 SET에 남는다.
 * 스트림이 끝나면 발급한 쪽({@code WebSocketChatHandler})이 {@link #revoke}로 바로 지운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegationTokenService {

    static final String KEY_PREFIX = "delegation:";
    /** chatStream 턴 상한 570초보다 길다 — 턴이 끝나기 전에 만료되지 않는다. */
    static final Duration TTL = Duration.ofSeconds(600);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;

    /** 토큰이 대신하는 사용자·권한·워크플로우. role은 authority 문자열 그대로(예: {@code ROLE_USER}). */
    public record Delegation(UUID userId, String role, UUID workflowId) {
    }

    /** 새 토큰을 만들어 Redis에 해시로 저장하고 원문을 돌려준다. */
    public String issue(UUID userId, String role, UUID workflowId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String value = JSON.createObjectNode()
            .put("userId", userId.toString())
            .put("role", role)
            .put("workflowId", workflowId.toString())
            .toString();
        redisTemplate.opsForValue().set(key(token), value, TTL);
        return token;
    }

    /** 살아 있는 토큰이면 그 위임 내용, 없거나(만료·폐기·위조) 값이 깨졌으면 empty. */
    public Optional<Delegation> resolve(String token) {
        String value = redisTemplate.opsForValue().get(key(token));
        if (value == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(value);
            return Optional.of(new Delegation(UUID.fromString(node.path("userId").asText()),
                node.path("role").asText(null), UUID.fromString(node.path("workflowId").asText())));
        } catch (Exception e) {
            // 값은 남기지 않는다 — 사용자 id가 들어 있다.
            log.warn("[Delegation] 위임 토큰 값을 읽을 수 없음 — {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 스트림 종료 시 즉시 폐기한다. null이면 아무것도 하지 않는다(발급 전 실패). */
    public void revoke(String token) {
        if (token != null) {
            redisTemplate.delete(key(token));
        }
    }

    static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK 구현체가 필수 제공 — 발생하지 않는다
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
