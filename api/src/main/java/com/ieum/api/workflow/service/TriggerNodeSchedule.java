package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TRIGGER 노드 {@code config}가 선언한 트리거 타입·cron. 워크플로우 최상위 {@code triggerType}·{@code cronExpression}
 * (Quartz 등록·웹훅 검사의 근거)을 정할 때 노드가 진실원이다 — REST 생성·수정({@link WorkflowService})과 AI 저장
 * ({@code ChatService})이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>{@code cron}은 노드 값 그대로다(Unix 5필드 → Quartz 변환은 저장 쪽 {@code normalizeCronExpression}이 한다).
 * 노드가 {@code triggerType}만 선언하고 cron을 빠뜨리면 {@code cron}은 null이다 — 요청 최상위나 저장된 값으로
 * 몰래 채우지 않는다(SCHEDULE이면 저장 쪽이 400으로 거부한다).
 */
public record TriggerNodeSchedule(TriggerType triggerType, String cron) {

    /**
     * @param nodes 저장할 노드 정의(Map). REST 수정은 직전 정의와 병합한 결과를 넘긴다
     * @return {@code config.triggerType}을 가진 TRIGGER 노드가 없으면 empty — 호출부가 요청 최상위 값을 쓴다(하위호환).
     *         그런 노드가 여럿이면 마지막 것이다
     */
    public static Optional<TriggerNodeSchedule> find(List<Map<String, Object>> nodes) {
        TriggerNodeSchedule found = null;
        for (Map<String, Object> node : nodes) {
            if ("TRIGGER".equals(node.get("type")) && node.get("config") instanceof Map<?, ?> config
                    && config.get("triggerType") != null) {
                found = new TriggerNodeSchedule(parseTriggerType(config.get("triggerType")),
                    config.get("cron") instanceof String value ? value : null);
            }
        }
        return Optional.ofNullable(found);
    }

    /** 알 수 없는 값이면 저장 전체를 거부한다 — REST가 잘못된 triggerType enum을 400으로 거부하는 것과 같다. */
    private static TriggerType parseTriggerType(Object value) {
        try {
            return TriggerType.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                "TRIGGER 노드의 triggerType이 올바르지 않습니다: " + value);
        }
    }
}
