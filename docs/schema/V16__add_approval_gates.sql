-- 사람 확인 노드 게이트(approval.*, IEUM-BE-119). psql로 한 번에 적용한다: psql -f V16__add_approval_gates.sql
-- 새 테이블만 만든다 — 기존 테이블·CHECK 제약은 건드리지 않는다. 엔티티는 ApprovalGate.
-- dev는 validate라 자동으로 생기지 않는다 — BE 머지 전에 dev PG에 직접(수동) 적용한다.
-- 옛 BE validate는 모르는 테이블을 무시하므로 먼저 적용해도 안전하다.
-- lock_timeout: 다른 DDL과 겹쳐 잠금을 오래 기다리지 않게 5초에 포기한다(V13·V15와 같다).
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';

-- 게이트 1개 = 실행·노드 1쌍. FK 없음(delay_queue_slots와 같다). 대기 판정은 workflow_runs 상태 조인으로 한다.
-- decision은 문자열 컬럼이다(@Enumerated 아님 — CHECK 제약이 생기지 않게).
CREATE TABLE approval_gates (
    id           UUID         PRIMARY KEY,
    execution_id UUID         NOT NULL,
    workflow_id  UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    node_id      VARCHAR(100) NOT NULL,
    event        VARCHAR(100) NOT NULL,
    view_json    TEXT         NOT NULL,
    webhook_id   UUID,
    deadline     TIMESTAMP    NOT NULL,
    remind_at    TIMESTAMP,
    notified_at  TIMESTAMP,
    reminded_at  TIMESTAMP,
    decision     VARCHAR(20),
    output_json  TEXT,
    decided_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uk_approval_gates_execution_node UNIQUE (execution_id, node_id)
);

CREATE INDEX idx_approval_gates_user_created ON approval_gates (user_id, created_at);

COMMIT;
