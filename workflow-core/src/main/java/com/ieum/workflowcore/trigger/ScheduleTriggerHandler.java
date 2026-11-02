package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.scheduler.ScheduleCron;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 스케줄(cron) — 발화 시각을 출력하고, schedule.every면 cron 원문도 낸다. 등록·해제는 Quartz 경로(WorkflowCrudService·WorkflowScheduleJob)가 한다. */
@Component
public class ScheduleTriggerHandler implements TriggerHandler {

    @Override
    public TriggerType type() {
        return TriggerType.SCHEDULE;
    }

    @Override
    public Map<String, Object> output(Node node, Map<String, Object> input, Map<String, Object> planted) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String cron = ScheduleCron.of(node.getId(), node.getEvent(), config);
        if (cron == null || cron.isBlank()) {
            throw new IllegalStateException("SCHEDULE 트리거에 cron 필드가 없습니다. nodeId: " + node.getId());
        }
        Map<String, Object> output = new HashMap<>();
        output.put("triggeredAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if ("schedule.every".equals(node.getEvent())) {
            output.put("cron", cron);   // 칸으로 만든 노드는 cron을 사용자가 쓴 적이 없어 내지 않는다
        }
        return output;
    }
}
