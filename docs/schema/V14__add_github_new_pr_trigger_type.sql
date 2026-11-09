-- 앱 트리거 GITHUB_NEW_PR(IEUM-BE-116)이 Hibernate가 테이블 생성 때 만든 trigger_type CHECK 제약에 막히지 않게 갱신한다.
-- ddl-auto: update는 기존 CHECK 제약을 고치지 않는다(V10과 같은 사정). 테스트(create-drop)는 새 스키마라 못 잡는다 —
-- BE 머지·배포 전에 대상 DB(dev·운영)에 직접 적용한다.
-- 적용 전 `\d workflows`·`\d workflow_runs`·`\d app_trigger_subscriptions`로 제약 이름을 확인할 것.
-- `\d app_trigger_subscriptions`로 제약 이름 확인 — 선례 없음. Hibernate 관례(<테이블>_<컬럼>_check)로 추정한 이름이라
-- 다르면 DROP 이름만 바꾼다. IF EXISTS를 쓰지 않는다 — 이름이 틀리면 DROP이 조용히 no-op이 되고 옛 제약이 남아
-- 여전히 막히므로, 적용 시 에러로 드러나게 둔다. DROP·ADD를 한 문장으로 묶어 DROP이 실패하면 ADD도 적용되지 않는다.
ALTER TABLE workflows
    DROP CONSTRAINT workflows_trigger_type_check,
    ADD CONSTRAINT workflows_trigger_type_check
        CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE', 'GMAIL_NEW_EMAIL', 'GITHUB_NEW_ISSUE', 'GITHUB_NEW_PR'));

ALTER TABLE workflow_runs
    DROP CONSTRAINT workflow_runs_trigger_type_check,
    ADD CONSTRAINT workflow_runs_trigger_type_check
        CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE', 'GMAIL_NEW_EMAIL', 'GITHUB_NEW_ISSUE', 'GITHUB_NEW_PR'));

ALTER TABLE app_trigger_subscriptions
    DROP CONSTRAINT app_trigger_subscriptions_trigger_type_check,
    ADD CONSTRAINT app_trigger_subscriptions_trigger_type_check
        CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE', 'GMAIL_NEW_EMAIL', 'GITHUB_NEW_ISSUE', 'GITHUB_NEW_PR'));
