package com.ieum.workflowcore.domain.enums;

/**
 * 워크플로우 트리거 종류. 종류별 동작(실행 출력·구독·접근 검증·노드 테스트)은 값마다 하나인
 * {@link com.ieum.workflowcore.trigger.TriggerHandler} 구현이 맡는다 — 공통 코드에는 종류별 분기를 두지 않는다.
 *
 * <p>값 추가는 workflow-core/CLAUDE.md "트리거 추가 절차"를 따른다: 카탈로그 이벤트(trigger_type)와 같은 커밋, 이름 20자 이하
 * ({@code workflows.trigger_type}이 VARCHAR(20)), 핸들러 {@code @Component}(없으면 api {@code CatalogStartupCheck}가 기동을
 * 막는다), PG CHECK 제약 수동 DDL — {@code workflows}·{@code workflow_runs}·{@code app_trigger_subscriptions} 세 테이블.
 * {@code ddl-auto: update}는 제약을 갱신하지 않는다.
 */
public enum TriggerType {
    /** 수동 실행 */
    MANUAL,
    /** Webhook 트리거 — 토큰 주소 수신(api WebhookService) */
    WEBHOOK,
    /** 스케줄 트리거 (cron) — Quartz */
    SCHEDULE,
    /** Gmail 새 메일 — BE 폴링(app_trigger_subscriptions) */
    GMAIL_NEW_EMAIL,
    /** GitHub 새 이슈 — GitHub App webhook(app_trigger_subscriptions, resource_key = repoId) */
    GITHUB_NEW_ISSUE
}
