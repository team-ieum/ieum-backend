-- IEUM-BE-45: 새 enum 값이 Hibernate가 테이블 생성 때 만든 CHECK 제약에 막히지 않게 갱신한다.
-- ddl-auto: update는 컬럼은 추가하지만 기존 CHECK 제약은 고치지 않는다(V7과 같은 사정).
-- 테스트(create-drop)는 새 스키마라 이 문제를 잡지 못한다 — 배포 전에 대상 DB에 직접 적용한다.
-- 적용 전 `\d workflow_runs`·`\d node_runs`로 제약 이름을 확인할 것. V7처럼 IF EXISTS를 쓰지 않는다 —
-- 이름이 틀리면 DROP이 조용히 no-op이 되고 옛 제약이 남아 여전히 막히므로, 적용 시 에러로 드러나게 둔다.
-- DROP·ADD를 한 문장으로 묶어(V7과 같다) DROP이 실패하면 ADD도 적용되지 않는다.
ALTER TABLE workflow_runs
    DROP CONSTRAINT workflow_runs_status_check,
    ADD CONSTRAINT workflow_runs_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'WAITING_APPROVAL'));

ALTER TABLE node_runs
    DROP CONSTRAINT node_runs_node_type_check,
    ADD CONSTRAINT node_runs_node_type_check
        CHECK (node_type IN ('TRIGGER', 'AI', 'CONDITION', 'HTTP', 'TRANSFORM', 'APPROVAL'));
