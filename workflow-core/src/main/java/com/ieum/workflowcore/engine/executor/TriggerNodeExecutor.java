package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRIGGER 노드 Executor.
 *
 * <p>워크플로우의 진입점으로, {@link com.ieum.workflowcore.engine.SyncExecutionRuntime}이
 * triggerData를 트리거 노드 출력 자리에 심은 뒤 이 Executor를 호출한다 — 재처리 때도 TRIGGER 노드는 스킵하지 않고
 * 다시 실행한다(node_runs의 마스킹된 출력 대신 복호된 triggerData로 같은 출력을 만든다).
 * TRIGGER 노드는 루프에서 첫 번째로 만나는 노드이므로 input을 그대로 output으로 전달하는 역할만 한다.
 * 앱 트리거(GMAIL_NEW_EMAIL·GITHUB_NEW_ISSUE)는 심어 둔 페이로드를 그대로 출력한다.
 */
@Slf4j
@Component
public class TriggerNodeExecutor implements NodeExecutor {

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
            // 앱 트리거: 런타임·노드 테스트가 트리거 노드 출력 자리에 심어 둔 정규화 페이로드가 곧 출력이다.
            // config(query·repoId)는 구독 설정이지 이벤트가 아니라 출력에 섞지 않는다(spec §2.4).
            if (TriggerType.appTriggerOf(node.getConfig().get("triggerType")) != null) {
                output.putAll(cursor.getContext().getNodeOutput(node.getId()));
                return ExecutorResult.success(output, System.currentTimeMillis() - startTime);
            }
            String triggerType = (String) node.getConfig().getOrDefault("triggerType", "MANUAL");

            switch (triggerType) {
                case "SCHEDULE" -> {
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
                    // WEBHOOK, MANUAL: 입력 데이터를 그대로 output으로 전달
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
