package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * IEUM 봇(GitHub App 설치) 명의 토큰 포트 — C7 PR 리뷰 제출을 봇 이름으로 낼 때만 쓴다. 실구현은 api
 * {@code DefaultGitHubBotTokenProvider}(사용자 설치 범위·쓰기 권한 확인 뒤 설치 토큰).
 *
 * <p>{@link GitHubTokenProvider}와 분리한 이유: api {@code GitHubApiReader}가 그 포트를 주입받으므로, 같은 어댑터에 넣으면
 * 빈 순환이 생긴다.
 */
public interface GitHubBotTokenProvider {

    /**
     * @return {@code owner/repo} 하나로 좁힌 설치 토큰(평문 — 로그 금지)
     * @throws com.ieum.common.exception.CustomException 거부(봇 미설정·설치 범위 밖·쓰기 권한 없음·장애) — 메시지가 노드 실패 사유
     */
    String getBotToken(UUID userId, String owner, String repo);
}
