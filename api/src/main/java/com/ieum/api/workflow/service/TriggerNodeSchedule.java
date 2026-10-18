package com.ieum.api.workflow.service;

import static com.ieum.workflowcore.catalog.NodeDerivation.echo;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.scheduler.CronConverter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 저장할 노드에서 계산한 워크플로우 트리거 타입·cron (IEUM-BE-92). PG {@code workflows.trigger_type}·{@code cron_expression}은
 * 이 값의 캐시다 — REST 생성·수정({@link WorkflowService})과 AI 저장({@code ChatService})이 같은 계산을 쓴다.
 *
 * <p>트리거 종류 = 첫 번째 TRIGGER 노드의 {@code event} → 카탈로그 {@code trigger_type}. 트리거 노드가 없으면 MANUAL·cron null.
 * {@code cron}은 SCHEDULE일 때 노드 {@code config.cron} 원문이다(Quartz 변환은 저장 쪽 {@code normalizeCronExpression}).
 * 노드에 cron이 없으면 compute는 null을 담고, 호출자가 {@link #assertCronPresent}로 어느 노드인지 밝혀 400을 낸다.
 */
public record TriggerNodeSchedule(TriggerType triggerType, String cron) {

    /** @param derivedNodes {@code NodeDerivation}을 거친 노드(type이 event로 계산돼 있다). REST 수정은 병합 결과다 */
    public static TriggerNodeSchedule compute(List<Map<String, Object>> derivedNodes, EventCatalog catalog) {
        for (Map<String, Object> node : derivedNodes) {
            if (!"TRIGGER".equals(node.get("type"))) {
                continue;
            }
            Object event = node.get("event");
            TriggerType type = (event instanceof String name ? catalog.triggerTypeOf(name) : Optional.<TriggerType>empty())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW,
                    "TRIGGER 노드의 event가 트리거 이벤트가 아닙니다: " + echo(event) + " (노드 " + echo(node.get("id")) + ")"));
            String cron = type == TriggerType.SCHEDULE && node.get("config") instanceof Map<?, ?> config
                && config.get("cron") instanceof String value ? value : null;
            return new TriggerNodeSchedule(type, cron);
        }
        return new TriggerNodeSchedule(TriggerType.MANUAL, null);
    }

    /**
     * SCHEDULE인데 노드 {@code config.cron}이 비었으면 400 {@code INVALID_CRON_EXPRESSION}("schedule.every 노드(<id>)의
     * config.cron이 필요합니다"). 저장 쪽 {@code validateScheduleConfig}의 일반 문구보다 먼저, 어느 노드인지 싣는다.
     * {@link #compute} 바로 뒤, {@link #assertMatchesRequest}보다 앞에서 부른다.
     *
     * @param derivedNodes compute에 넘긴 그 노드 목록(첫 TRIGGER 노드의 id를 메시지에 싣는다)
     */
    public void assertCronPresent(List<Map<String, Object>> derivedNodes) {
        if (triggerType != TriggerType.SCHEDULE || (cron != null && !cron.isBlank())) {
            return;
        }
        Object nodeId = derivedNodes.stream().filter(node -> "TRIGGER".equals(node.get("type")))
            .findFirst().map(node -> node.get("id")).orElse(null);
        throw new CustomException(ErrorCode.INVALID_CRON_EXPRESSION,
            "schedule.every 노드(" + echo(nodeId) + ")의 config.cron이 필요합니다");
    }

    /**
     * 요청 최상위 {@code triggerType}·{@code cronExpression}이 계산값과 맞는지 본다. null(cron은 빈 문자열 포함)이면 통과,
     * 다르면 400 {@code INVALID_WORKFLOW}(메시지에 계산값). cron은 둘 다 {@link CronConverter#toQuartz}로 정규화해 비교하고,
     * 계산값이 SCHEDULE이 아닌데 cron을 보내면 400이다.
     */
    public void assertMatchesRequest(TriggerType requestedType, String requestedCron) {
        if (requestedType != null && requestedType != triggerType) {
            throw mismatch("triggerType(" + requestedType + ")");
        }
        if (requestedCron == null || requestedCron.isBlank()) {
            return;
        }
        if (triggerType != TriggerType.SCHEDULE || cron == null || cron.isBlank()
                || !CronConverter.toQuartz(requestedCron).equals(CronConverter.toQuartz(cron))) {
            throw mismatch("cronExpression(" + echo(requestedCron) + ")");
        }
    }

    private CustomException mismatch(String requested) {
        return new CustomException(ErrorCode.INVALID_WORKFLOW, "요청의 " + requested
            + "이 노드에서 계산한 값과 다릅니다. 계산값: triggerType=" + triggerType
            + (cron != null ? ", cronExpression=" + echo(cron) : "")
            + " — 최상위 값은 보내지 않거나 계산값과 같게 보내세요.");
    }
}
