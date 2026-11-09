-- 기다리기 노드(DELAY, IEUM-BE-117) — 1부: enum CHECK 제약 갱신.
-- WAITING_DELAY 실행 상태와 DELAY 노드 타입이 Hibernate가 테이블 생성 때 만든 CHECK 제약에 막히지 않게 갱신한다.
-- ddl-auto: update는 기존 CHECK 제약을 고치지 않는다(V8·V9와 같은 사정). 테스트(create-drop)는 새 스키마라
-- 이 문제를 잡지 못한다 — BE 머지 전에 dev PG에 직접(수동) 적용한다.
-- 적용 전 `\d workflow_runs`·`\d node_runs`로 제약 이름을 확인할 것. IF EXISTS를 쓰지 않는다 — 이름이 틀리면
-- DROP이 조용히 no-op이 되고 옛 제약이 남아 여전히 막히므로, 적용 시 에러로 드러나게 둔다.
-- DROP·ADD를 한 문장으로 묶어(V8·V9와 같다) DROP이 실패하면 ADD도 적용되지 않는다.
ALTER TABLE workflow_runs
    DROP CONSTRAINT workflow_runs_status_check,
    ADD CONSTRAINT workflow_runs_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'WAITING_APPROVAL', 'WAITING_DELAY'));

ALTER TABLE node_runs
    DROP CONSTRAINT node_runs_node_type_check,
    ADD CONSTRAINT node_runs_node_type_check
        CHECK (node_type IN ('TRIGGER', 'AI', 'CONDITION', 'HTTP', 'TRANSFORM', 'APPROVAL', 'ACTION', 'DELAY'));

-- 2부: 기다리기 큐(delay.queue). 사용자·이름 단위 행 하나를 잠가 "마지막 내보낼 시각 + 간격"으로 차례를 정한다.
-- 엔티티는 DelayQueue. dev는 validate라 자동으로 생기지 않는다 — 새 테이블이라 옛 BE validate도 통과하므로 먼저 적용해도 안전하다.
CREATE TABLE delay_queues (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,
    name            VARCHAR(100) NOT NULL,
    last_release_at TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_delay_queues_user_name UNIQUE (user_id, name)
);

-- 3부: 실행 행에 기다리기 재개 시각·대기 노드. 엔티티는 WorkflowExecution(resumeAt·waitingDelayNodes).
-- nullable이라 옛 BE validate도 통과 — 먼저 적용해도 안전하다. 인덱스는 재개 sweeper 조회(status, resume_at)용.
ALTER TABLE workflow_runs
    ADD COLUMN resume_at TIMESTAMP,
    ADD COLUMN waiting_delay_nodes TEXT;

CREATE INDEX idx_workflow_runs_status_resume_at ON workflow_runs (status, resume_at);
