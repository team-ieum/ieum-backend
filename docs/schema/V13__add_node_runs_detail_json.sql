-- 조건 분기 판단 근거·경고 (IEUM-BE-113). 엔티티 WorkflowExecutionLog.detailJson.
-- dev 배포는 ddl-auto validate라 이 DDL을 머지 전에 손으로 적용해야 한다(2026-11-06 dev 적용 완료).
-- nullable 컬럼 추가라 기존 행을 고치지 않고, 옛 버전 BE의 validate도 통과한다.
SET lock_timeout = '5s';
ALTER TABLE node_runs ADD COLUMN IF NOT EXISTS detail_json TEXT;
