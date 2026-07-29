package com.ieum.workflowcore.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 멱등성 마커/헤더에 쓸 결정적 키를 만드는 순수 함수.
 *
 * <p>{@code sha256(executionId + ":" + nodeId)}의 앞 32자 hex를 반환한다. 재시도 회차와
 * 무관하게 같은 executionId·nodeId 쌍이면 항상 같은 키가 나와야 중복 호출 차단이 성립한다
 * — attempt 번호는 절대 키에 섞지 않는다.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String generate(String executionId, String nodeId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((executionId + ":" + nodeId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK 구현체가 필수 제공 — 발생하지 않는다
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
