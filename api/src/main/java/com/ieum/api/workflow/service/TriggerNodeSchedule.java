package com.ieum.api.workflow.service;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 워크플로우 최상위 {@code triggerType}·{@code cronExpression}을 노드에서 계산한 값이다. 이 값은 Quartz 등록과 웹훅 검사의
 * 근거가 된다. 트리거 종류의 진실원은 TRIGGER 노드의 {@code event}다(IEUM-BE-92). REST 생성·수정({@link WorkflowService})과
 * AI 저장({@code ChatService})이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>{@code cron}은 노드 값 원문이다. Unix 5필드를 Quartz로 바꾸는 일은 저장 쪽 {@code normalizeCronExpression}이 한다.
 */
public record TriggerNodeSchedule(TriggerType triggerType, String cron) {

    private static final TriggerNodeSchedule NONE = new TriggerNodeSchedule(TriggerType.MANUAL, null);

    /**
     * @param derivedNodes {@code NodeDerivation}을 거친 노드. type과 event가 카탈로그와 맞는다
     * @return 첫 TRIGGER 노드의 event가 정한 트리거 종류. SCHEDULE이면 config.cron 원문도 함께 담는다(문자열일 때만).
     *         TRIGGER 노드가 없으면 (MANUAL, null). 여럿이면 첫 번째를 쓴다. 실행 쪽 선택과 같다
     */
    public static TriggerNodeSchedule compute(List<Map<String, Object>> derivedNodes, EventCatalog catalog) {
        for (Map<String, Object> node : derivedNodes) {
            if (!"TRIGGER".equals(node.get("type"))) {
                continue;
            }
            TriggerType type = catalog.triggerTypeOf((String) node.get("event")).orElseThrow(() ->
                new IllegalStateException("트리거 event에 trigger_type이 없습니다: " + node.get("event")));
            Object cron = node.get("config") instanceof Map<?, ?> config ? config.get("cron") : null;
            return new TriggerNodeSchedule(type,
                type == TriggerType.SCHEDULE && cron instanceof String value ? value : null);
        }
        return NONE;
    }

    /**
     * 과도기용 메서드다. TRIGGER 노드가 없으면 empty를 돌려주고, 호출부는 요청 최상위 값이나 저장값으로 폴백한다.
     * IEUM-BE-92 Task 3이 이 메서드와 폴백을 함께 지운다.
     */
    public static Optional<TriggerNodeSchedule> find(List<Map<String, Object>> derivedNodes, EventCatalog catalog) {
        return derivedNodes.stream().anyMatch(node -> "TRIGGER".equals(node.get("type")))
            ? Optional.of(compute(derivedNodes, catalog)) : Optional.empty();
    }
}
