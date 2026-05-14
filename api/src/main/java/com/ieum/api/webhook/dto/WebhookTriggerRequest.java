package com.ieum.api.webhook.dto;

import java.util.Map;
import lombok.Getter;

/**
 * 외부 시스템이 Webhook으로 워크플로우를 트리거할 때 전달하는 요청 바디.
 *
 * <p>payload는 자유 형식 JSON이며, 워크플로우 내부에서 변수 참조로 사용할 수 있다.
 * 요청 바디 없이 호출해도 무방하다 (payload = null → 빈 Map으로 처리).
 */
@Getter
public class WebhookTriggerRequest {

    /** 워크플로우로 전달할 임의 데이터. 노드에서 {{triggerData.xxx}} 형태로 참조 가능. */
    private Map<String, Object> payload;
}
