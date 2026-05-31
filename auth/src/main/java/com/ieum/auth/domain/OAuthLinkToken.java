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
 * Google OAuth 계정 연동(account linking)용 일회용 토큰.
 *
 * <p>인증된 유저가 연동을 시작할 때 발급되어 Redis에 저장된다. OAuth authorization 요청의
 * {@code state}로 사용되어 Google 라운드트립을 거쳐 콜백으로 돌아오며, 콜백에서 이 토큰으로
 * 현재 유저를 식별해 Google 계정을 연동한다. 사용 즉시 삭제(1회용)된다.
 *
 * <p>userId를 클라이언트가 직접 전달하지 않고 서버사이드에서만 매핑하므로 위변조에 안전하다.
 */
@RedisHash(value = "oauthLinkToken")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthLinkToken {

    @Id
    private String token;

    /** 연동을 요청한 현재 로그인 유저 ID. */
    private UUID userId;

    /** 요청한 scope 그룹 (콤마 구분). */
    private String scopeGroups;

    @TimeToLive
    private Long ttl;  // 300 seconds (5 minutes)
}
