package com.ieum.api.webhook.dto;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WebhookTriggerRequest {

    private Map<String, Object> payload;
}
