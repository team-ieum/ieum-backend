package com.ieum.api.config;

import com.ieum.auth.service.SlackTokenService;
import com.ieum.workflowcore.engine.executor.SlackTokenProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** workflow-core 포트 SlackTokenProvider → auth SlackTokenService. 이 빈이 있으면 StubSlackTokenProvider는 꺼진다. */
@Component
@RequiredArgsConstructor
public class DefaultSlackTokenProvider implements SlackTokenProvider {

    private final SlackTokenService slackTokenService;

    @Override
    public String getValidBotToken(UUID userId) {
        return slackTokenService.getValidBotToken(userId);
    }
}
