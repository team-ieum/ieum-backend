package com.ieum.auth.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

/**
 * 발송된 이메일 인증코드. id는 {@code purpose:email}.
 *
 * <p>{@code @TimeToLive}는 save할 때마다 TTL을 다시 건다. 시도 횟수를 올려 저장할 때 TTL이 처음
 * 값으로 늘어나지 않도록 {@code expiresAt}에서 남은 시간을 계산해 넣는다.
 */
@RedisHash(value = "emailVerificationCode")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationCode {

    @Id
    private String id;

    private String code;

    private int attempts;

    /** 발송 시각(epoch ms). 재발송 쿨다운 판단용. */
    private long sentAt;

    /** 만료 시각(epoch ms). */
    private long expiresAt;

    @TimeToLive
    private Long ttl;

    public void increaseAttempts(long now) {
        this.attempts++;
        this.ttl = Math.max(1, (expiresAt - now) / 1000);
    }
}
