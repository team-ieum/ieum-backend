-- ACTION 노드 타입(앱 도구 단독 실행)이 Hibernate가 테이블 생성 때 만든 node_type CHECK 제약에 막히지 않게 갱신한다.
-- ddl-auto: update는 기존 CHECK 제약을 고치지 않는다(V7·V8과 같은 사정). 제약에 걸린 INSERT는
-- SyncExecutionRuntime.saveExecutionLog의 try/catch가 경고로 삼키므로, 적용하지 않으면 실행은 성공해도
-- node_runs 이력만 조용히 유실된다. 테스트(create-drop)는 새 스키마라 이 문제를 잡지 못한다 —
-- 배포 전에 대상 DB(dev·운영)에 직접 적용한다.
-- 적용 전 `\d node_runs`로 제약 이름을 확인할 것. V8처럼 IF EXISTS를 쓰지 않는다 — 이름이 틀리면
-- DROP이 조용히 no-op이 되고 옛 제약이 남아 여전히 막히므로, 적용 시 에러로 드러나게 둔다.
-- DROP·ADD를 한 문장으로 묶어(V7·V8과 같다) DROP이 실패하면 ADD도 적용되지 않는다.
ALTER TABLE node_runs
    DROP CONSTRAINT node_runs_node_type_check,
    ADD CONSTRAINT node_runs_node_type_check
        CHECK (node_type IN ('TRIGGER', 'AI', 'CONDITION', 'HTTP', 'TRANSFORM', 'APPROVAL', 'ACTION'));
