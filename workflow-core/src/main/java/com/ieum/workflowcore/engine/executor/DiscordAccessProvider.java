package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * Discord 봇 토큰 + 연결 서버 포트 — api {@code DefaultDiscordAccessProvider}가 env 토큰과 링크 저장소로 만든다.
 * Slack 포트처럼 미연결이면 예외를 던진다 — 헤더 없이 agent까지 가서 애매하게 실패하지 않게.
 */
public interface DiscordAccessProvider {

    /** @throws com.ieum.common.exception.CustomException DISCORD_NOT_CONNECTED(링크 0) */
    DiscordAccess get(UUID userId);
}
