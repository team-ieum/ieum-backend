# Workflow Core Module Context

## 역할
워크플로우 엔진의 핵심 비즈니스 로직. 워크플로우 CRUD, 버전 관리, 실행 엔진을 담당한다.

## 현재 상태
Phase 3에서 구현 예정. 현재는 빈 모듈 (`.gitkeep`만 존재)

## 핵심 설계 (구현 시 참고)

### ExecutionCursor (Queue 기반 노드 순회)
- GraphResolver 대신 채택 — CONDITION 분기 시 동적 노드 결정에 유리
- Queue에 다음 실행할 노드 ID를 넣고, 하나씩 꺼내며 실행
- 조건 분기 시 평가 결과에 따라 true/false 경로의 노드만 큐에 추가

### 변수 참조 시스템
- 문법: `{{nodes.<node_uuid>.output.<field>}}`
- VariableResolver가 실행 시점에 이전 노드의 output에서 값을 치환
- 중첩 참조 불가 (1단계만 허용)

### 노드 타입
| 카테고리 | 타입 | 설명 |
|---------|------|------|
| 트리거 | webhook_trigger, schedule_trigger, gmail_trigger, sheets_trigger, notion_trigger | 워크플로우 시작점 (1개만 허용) |
| 액션 | gmail_action, sheets_action, drive_action, calendar_action, notion_action | 외부 서비스 실행 |
| AI | ai_summarize, ai_classify, ai_extract | AI 기반 텍스트 처리 |
| 로직 | condition, delay, error_handler | 분기/대기/에러 처리 |

### 실행 모드
- `SyncExecutionRuntime` (MVP): 동기 실행, 노드 하나씩 순차 처리
- `AsyncExecutionRuntime` (추후): 비동기 실행, 이벤트 드리븐
- `ExecutionRuntime` 인터페이스로 추상화 — 구현체 교체 시 리팩토링 불필요

### 재시도 추적
- `node_runs` 테이블에 `retry_count` 기록
- 실패 시 error_handler 노드로 분기 가능

## DB 테이블
- `workflows` — 워크플로우 정의 (name, description, status, trigger_type)
- `workflow_versions` — 버전별 nodes/edges (JSONB), version_number, is_active
- `workflow_runs` — 실행 이력 (status, started_at, finished_at, trigger_data JSONB)
- `node_runs` — 노드별 실행 결과 (input/output JSONB, status, retry_count, error_message)

## 주의사항
- node_runs의 input/output JSONB에 자격증명(토큰, API Key) 포함 절대 금지 — 저장 전 필터링
- 워크플로우당 트리거 노드는 1개만 허용 — 검증 로직 필수
- 노드 config에 실제 토큰/키 값 저장 금지 — credential_id 참조만 허용
- Quartz 스케줄러 사용 (schedule_trigger용)

## 패키지 구조 (예정)
```
com.ieum.workflowcore
├── domain/        # Workflow, WorkflowVersion, WorkflowRun, NodeRun
├── engine/        # ExecutionCursor, ExecutionRuntime, SyncExecutionRuntime
├── node/          # NodeExecutor 인터페이스, 노드 타입별 구현체
├── repository/    # 각 엔티티 Repository + QueryRepository
├── service/       # WorkflowService, ExecutionService, VersionService
└── variable/      # VariableResolver (변수 치환 엔진)
```
