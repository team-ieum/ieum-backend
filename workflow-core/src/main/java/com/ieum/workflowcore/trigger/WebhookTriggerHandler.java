package com.ieum.workflowcore.trigger;

import com.ieum.workflowcore.domain.enums.TriggerType;
import org.springframework.stereotype.Component;

/**
 * Webhook — 받은 페이로드가 곧 출력이다(output 기본값). config(includeRaw)는 수신 설정이라 출력에 섞지 않는다.
 * 노드 테스트는 토큰 주소로 수신을 기다린다.
 */
@Component
public class WebhookTriggerHandler implements TriggerHandler {

    @Override
    public TriggerType type() {
        return TriggerType.WEBHOOK;
    }

    @Override
    public NodeTestMode nodeTestMode() {
        return NodeTestMode.LISTEN;
    }
}
