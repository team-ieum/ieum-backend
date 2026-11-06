package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 수동 실행 — 심어 둔 실행 입력(triggerData·노드 테스트 input)에 치환된 config를 덮어 출력한다(키가 겹치면 config).
 * 실행 입력을 config 자기 참조로만 받으면 agent 정적 검증이 REF_SELF로 실행을 막아 뒤 노드가 입력을 못 읽는다.
 */
@Component
public class ManualTriggerHandler implements TriggerHandler {

    @Override
    public TriggerType type() {
        return TriggerType.MANUAL;
    }

    @Override
    public Map<String, Object> output(Node node, Map<String, Object> input, Map<String, Object> planted) {
        Map<String, Object> output = new HashMap<>(planted);
        output.putAll(input);
        return output;
    }
}
