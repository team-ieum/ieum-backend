package com.ieum.workflowcore.domain.enums;

public enum TriggerType {
    /** 수동 실행 */
    MANUAL,
    /** Webhook 트리거 */
    WEBHOOK,
    /** 스케줄 트리거 (cron) */
    SCHEDULE
}
