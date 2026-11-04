package com.ieum.api.config;

import com.ieum.api.usage.domain.AiTokenUsage.UsageSource;
import com.ieum.api.usage.service.AiTokenUsageRecorder;
import com.ieum.workflowcore.engine.executor.AiUsageRecorder;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * AiUsageRecorder 실제 구현체 (어댑터). 워크플로우 AI 노드 호출을 {@code ai_token_usage}에 WORKFLOW로 남긴다.
 * 저장·모델 결정은 채팅과 공유하는 {@link AiTokenUsageRecorder}가 한다.
 *
 * <p>{@code @Primary}로 스캔 순서와 무관하게 항상 이 빈이 선택되도록 고정한다
 * ({@code DefaultBetaPlatformProvider}와 같은 이유). 예외를 던지지 않는다 — 포트 계약.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DefaultAiUsageRecorder implements AiUsageRecorder {

    private final AiTokenUsageRecorder recorder;
    private final WorkflowExecutionRepository executionRepository;

    @Override
    public void record(AiUsageCall call) {
        try {
            recorder.record(AiTokenUsageRecorder.UsageCall.builder()
                .userId(call.userId())
                .provider(call.provider())
                .requestedModel(call.requestedModel())
                .reportedModel(call.reportedModel())
                .platformKey(call.platformKey())
                .source(UsageSource.WORKFLOW)
                .workflowId(workflowIdOf(call.executionId()))
                .executionId(call.executionId())
                .inputTokens(call.promptTokens())
                .outputTokens(call.completionTokens())
                .totalTokens(call.totalTokens())
                .build());
        } catch (Exception e) {
            log.warn("[DefaultAiUsageRecorder] 사용량 기록 실패 — userId: {}", call.userId(), e);
        }
    }

    /** 실행 컨텍스트엔 workflowId를 두지 않는 규칙이라(workflow-core CLAUDE.md) 실행 ID로 찾는다. */
    private UUID workflowIdOf(UUID executionId) {
        if (executionId == null) {
            return null;
        }
        try {
            return executionRepository.findWorkflowIdById(executionId).orElse(null);
        } catch (Exception e) {
            // 참조값일 뿐이다 — 조회 실패로 사용량 행까지 잃지 않는다
            log.warn("[DefaultAiUsageRecorder] workflowId 조회 실패 — executionId: {}", executionId, e);
            return null;
        }
    }
}
