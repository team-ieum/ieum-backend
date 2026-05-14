package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * 사용자의 유효한 Google Access Token을 조회하는 포트 인터페이스.
 *
 * <p>auth 모듈의 GoogleTokenService가 이 인터페이스를 구현하여
 * api 모듈의 DefaultGoogleTokenProvider를 통해 주입된다.
 *
 * <p>workflow-core는 auth 모듈에 의존하지 않으므로 인터페이스로 포트를 정의하고,
 * api 모듈에서 어댑터(DefaultGoogleTokenProvider)로 연결한다.
 */
public interface GoogleTokenProvider {

    /**
     * 유효한 Google Access Token(평문)을 반환한다.
     * 토큰이 만료된 경우 내부적으로 자동 갱신한다.
     *
     * @param userId 조회할 사용자 ID
     * @return 유효한 Google Access Token 원문
     * @throws com.ieum.common.exception.CustomException ACCOUNT_NOT_CONNECTED — Google 연동 계정 없음
     * @throws com.ieum.common.exception.CustomException AUTHENTICATION_REQUIRED — 재인증 필요
     */
    String getValidAccessToken(UUID userId);
}
