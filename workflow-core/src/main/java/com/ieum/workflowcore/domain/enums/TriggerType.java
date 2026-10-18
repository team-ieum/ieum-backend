package com.ieum.workflowcore.domain.enums;

/**
 * 트리거 종류. 종류별 동작(실행 출력·구독·접근 검증·노드 테스트)은 {@link com.ieum.workflowcore.trigger.TriggerHandler} 구현
 * 하나가 맡는다 — 값을 추가하면 같은 type의 핸들러 빈을 함께 등록한다(없으면 api 기동이 실패한다).
 *
 * <p>값을 추가하면 PG CHECK 제약({@code workflows}·{@code workflow_runs}의 trigger_type — {@code docs/schema/V10}, 그리고
 * Hibernate가 ddl-auto로 만든 {@code app_trigger_subscriptions.trigger_type})을 수동 DDL로 고쳐야 한다.
 * ddl-auto update는 제약을 갱신하지 않는다.
 */
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
    GITHUB_NEW_ISSUE
}
