package com.ieum.api.webhookcredential.domain;

/**
 * 웹훅 발송 대상 서비스. OAuth가 아닌 Incoming Webhook URL로 메시지를 발송하는 서비스만 포함한다.
 */
public enum WebhookProvider {
    SLACK,
    DISCORD
}
