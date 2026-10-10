package com.ieum.workflowcore.domain.enums;

public enum TriggerType {
    /** 수동 실행 */
    MANUAL,
    /** Webhook 트리거 */
    WEBHOOK,
    /** 스케줄 트리거 (cron) */
    SCHEDULE,
    /** Gmail 새 메일 — BE 폴링(app_trigger_subscriptions) */
    GMAIL_NEW_EMAIL,
    /** GitHub 새 이슈 — GitHub App webhook(app_trigger_subscriptions, resource_key = repoId) */
    GITHUB_NEW_ISSUE;

    /**
     * 외부 앱 이벤트로 발화하는 트리거인가. 이 값들은 출력이 트리거 config가 아니라 정규화된 이벤트 페이로드이고,
     * 활성 상태가 {@code app_trigger_subscriptions} 행으로 관리된다.
     *
     * <p>값을 추가하면 PG CHECK 제약({@code workflows}·{@code workflow_runs}의 trigger_type)을 수동 DDL로 고쳐야 한다
     * ({@code docs/schema/V10}). ddl-auto update는 제약을 갱신하지 않는다.
     */
    public boolean isAppTrigger() {
        return this == GMAIL_NEW_EMAIL || this == GITHUB_NEW_ISSUE;
    }

    /** 노드 {@code config.triggerType} 값이 앱 트리거 이름이면 그 값, 아니면(없음·다른 트리거·모르는 값) null. */
    public static TriggerType appTriggerOf(Object declared) {
        if (!(declared instanceof String name)) {
            return null;
        }
        for (TriggerType type : values()) {
            if (type.isAppTrigger() && type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
