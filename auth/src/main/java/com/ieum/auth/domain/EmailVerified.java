package com.ieum.auth.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

/** 인증코드 검증을 통과한 이메일 표시. id는 {@code purpose:email}. 사용 즉시 삭제하는 1회용이다. */
@RedisHash(value = "emailVerified")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerified {

    @Id
    private String id;

    @TimeToLive
    private Long ttl;
}
