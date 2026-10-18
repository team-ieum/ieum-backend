package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 스케줄(cron) — 발화 시각과 cron을 출력한다. 등록·해제는 Quartz 경로(WorkflowCrudService·WorkflowScheduleJob)가 한다. */
@Component
public class ScheduleTriggerHandler implements TriggerHandler {

    @Override
    public TriggerType type() {
        return TriggerType.SCHEDULE;
    }

    @Override
    public Map<String, Object> output(Node node, Map<String, Object> input, Map<String, Object> planted) {
        String cron = (String) node.getConfig().get("cron");
        if (cron == null || cron.isBlank()) {
            throw new IllegalStateException("SCHEDULE 트리거에 cron 필드가 없습니다. nodeId: " + node.getId());
        }
        Map<String, Object> output = new HashMap<>();
        output.put("triggeredAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        output.put("cron", cron);
        return output;
    }
}
