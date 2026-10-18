package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 수동 실행 — 치환된 config를 그대로 출력한다(심어 둔 페이로드는 config가 자기 참조할 때만 치환으로 들어온다). */
@Component
public class ManualTriggerHandler implements TriggerHandler {

    @Override
    public TriggerType type() {
        return TriggerType.MANUAL;
    }

    @Override
    public Map<String, Object> output(Node node, Map<String, Object> input, Map<String, Object> planted) {
        return new HashMap<>(input);
    }
}
