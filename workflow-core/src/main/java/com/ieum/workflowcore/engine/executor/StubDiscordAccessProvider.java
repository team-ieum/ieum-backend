package com.ieum.workflowcore.engine.executor;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** api의 DefaultDiscordAccessProvider가 없을 때(workflow-core 단독)만 뜬다 — 실제 구현과 같은 예외를 던진다. */
@Component
@ConditionalOnMissingBean(value = DiscordAccessProvider.class, ignored = StubDiscordAccessProvider.class)
public class StubDiscordAccessProvider implements DiscordAccessProvider {

    @Override
    public DiscordAccess get(UUID userId) {
        throw new CustomException(ErrorCode.DISCORD_NOT_CONNECTED);
    }
}
