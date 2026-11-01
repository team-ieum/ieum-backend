package com.ieum.auth.domain;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

/**
 * Slack 설치 1회분 state. nonceHash는 authorize 때 브라우저 쿠키로 준 nonce의 SHA-256(hex) — 콜백 쿠키와 대조해
 * 콜백을 설치를 시작한 브라우저에 묶는다(spec S1, RFC 6749 §10.12). 원문 nonce는 저장하지 않는다.
 */
@RedisHash(value = "slackOAuthState")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackOAuthState {

    @Id
    private String state;

    private UUID userId;

    private String nonceHash;

    @TimeToLive
    private Long ttl;  // 300초
}
