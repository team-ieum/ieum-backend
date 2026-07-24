package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * 베타 플랫폼 키(IEUM 소유 Gemini 키) 자격·쿼터 게이트 포트 인터페이스.
 * api 모듈의 어댑터(DefaultBetaPlatformProvider)가 User.betaAccess + BetaQuotaService(Redis)로 구현하여 주입된다.
 *
 * <p>workflow-core는 auth/api 모듈(User, Redis 쿼터)에 직접 의존하지 않으므로,
 * 의존성 역전을 위해 이 포트로 베타 자격을 판단하고 쿼터를 예약/기록한다.
 *
 * <p>BYOK(credentialId 존재) &gt; self-hosted(ADMIN/TESTER role) &gt; platform(베타) &gt; 거부 순서의
 * 최종 판단은 ieum-agent의 크레덴셜 미들웨어가 X-User-Role로 다시 확인하므로,
 * 이 포트는 "베타 쿼터 게이트"만 책임진다.
 */
public interface BetaPlatformProvider {

    /**
     * 사용자가 베타 플랫폼 키 폴백 대상인지 확인한다 (전역 kill-switch + User.betaAccess).
     *
     * @param userId 사용자 UUID
     * @return 베타 자격이 있으면 true
     */
    boolean isBetaEligible(UUID userId);

    /**
     * 호출 전 쿼터를 예약한다 (일일 호출수 INCR + 토큰 예산 확인). 쿼터 초과 시 예외를 던진다.
     *
     * @param userId 사용자 UUID
     */
    void reserveQuota(UUID userId);

    /**
     * 응답 usage의 totalTokens만큼 사용량을 사후 기록한다.
     *
     * @param userId     사용자 UUID
     * @param totalTokens 이번 호출에서 소비한 총 토큰 수
     */
    void recordTokens(UUID userId, long totalTokens);

    /**
     * reserveQuota로 예약(INCR)했지만 이후 agent 호출이 실패해 실제로는 쓰이지 않은 일일 호출권을 환불한다.
     * reserveQuota 자체가 쿼터 초과 등으로 실패한 경우는 호출하지 않는다(그 경우는 예약이 반영되지 않았다).
     *
     * @param userId 사용자 UUID
     */
    void releaseDailyCall(UUID userId);
}
