package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * Slack 봇 토큰 포트 — api {@code DefaultSlackTokenProvider}가 auth {@code SlackTokenService}에 잇는다.
 * Notion·GitHub 포트(Optional + warn)와 달리 미연결이면 예외를 던진다 — 헤더 없이 agent까지 가서 애매하게 실패하지 않게.
 */
public interface SlackTokenProvider {

    /**
     * @return 유효한 봇 토큰(평문). 만료 임박이면 갱신한 값
     * @throws com.ieum.common.exception.CustomException SLACK_NOT_CONNECTED · AUTHENTICATION_REQUIRED · TOKEN_REFRESH_FAILED
     */
    String getValidBotToken(UUID userId);
}
