package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRIGGER 노드 Executor.
 *
 * <p>워크플로우의 진입점으로, {@link com.ieum.workflowcore.engine.SyncExecutionRuntime}이
 * triggerData를 트리거 노드 출력 자리에 심은 뒤 이 Executor를 호출한다 — 재처리 때도 TRIGGER 노드는 스킵하지 않고
 * 다시 실행한다(node_runs의 마스킹된 출력 대신 복호된 triggerData로 같은 출력을 만든다).
 * 트리거 종류는 노드 {@code event}의 카탈로그 {@code trigger_type}이다(없거나 트리거 event가 아니면 MANUAL).
 * 앱 트리거(GMAIL_NEW_EMAIL·GITHUB_NEW_ISSUE)와 WEBHOOK은 심어 둔 페이로드를 그대로 출력한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerNodeExecutor implements NodeExecutor {

    private final EventCatalog eventCatalog;

    @Override
    public NodeType getNodeType() {
        return NodeType.TRIGGER;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[TriggerExecutor] 노드 실행 — nodeId: {}", node.getId());

        try {
            Map<String, Object> output = new HashMap<>();
            TriggerType triggerType = eventCatalog.triggerTypeOf(node.getEvent()).orElse(TriggerType.MANUAL);
            // 앱 트리거·WEBHOOK: 런타임·노드 테스트가 트리거 노드 출력 자리에 심어 둔 페이로드가 곧 출력이다.
            // config(query·repoId·includeRaw)는 수신 설정이지 이벤트가 아니라 출력에 섞지 않는다(spec §2.4).
            if (triggerType.isAppTrigger() || triggerType == TriggerType.WEBHOOK) {
                output.putAll(cursor.getContext().getNodeOutput(node.getId()));
                return ExecutorResult.success(output, System.currentTimeMillis() - startTime);
            }

            switch (triggerType) {
                case SCHEDULE -> {
                    String cron = (String) node.getConfig().get("cron");
                    if (cron == null || cron.isBlank()) {
                        throw new IllegalStateException(
                            "SCHEDULE 트리거에 cron 필드가 없습니다. nodeId: " + node.getId());
                    }
                    String triggeredAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    output.put("triggeredAt", triggeredAt);
                    output.put("cron", cron);
                    log.debug("[TriggerExecutor] SCHEDULE 트리거 — cron: {}, triggeredAt: {}", cron, triggeredAt);
                }
                default -> {
                    // MANUAL: 치환된 config를 그대로 output으로 전달
                    if (input != null) {
                        output.putAll(input);
                    }
                    log.debug("[TriggerExecutor] {} 트리거 — outputKeys: {}", triggerType, output.keySet());
                }
            }

            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[TriggerExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
