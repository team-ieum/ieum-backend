package com.ieum.workflowcore.engine.executor;

import java.util.List;

/**
 * Discord 봇 도구 실행에 싣는 값 — 봇 토큰(BE env 하나, D3)과 그 사용자가 연결한 서버 ID(agent 소유 검사의 허용 목록, D4).
 * toString은 서버 개수만 — 기본 record toString은 토큰 평문을 로그에 찍는다.
 */
public record DiscordAccess(String botToken, List<String> guildIds) {

    public DiscordAccess {
        guildIds = List.copyOf(guildIds);
    }

    @Override
    public String toString() {
        return "DiscordAccess[guildCount=" + guildIds.size() + "]";
    }
}
