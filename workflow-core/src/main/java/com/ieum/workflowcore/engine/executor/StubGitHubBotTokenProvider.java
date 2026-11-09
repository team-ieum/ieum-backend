package com.ieum.workflowcore.engine.executor;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** api {@code DefaultGitHubBotTokenProvider}가 없을 때(workflow-core 단독)의 Stub — 늘 봇 미설정. */
@Component
@ConditionalOnMissingBean(value = GitHubBotTokenProvider.class, ignored = StubGitHubBotTokenProvider.class)
public class StubGitHubBotTokenProvider implements GitHubBotTokenProvider {

    @Override
    public String getBotToken(UUID userId, String owner, String repo) {
        throw new CustomException(ErrorCode.GITHUB_BOT_NOT_CONFIGURED);
    }
}
