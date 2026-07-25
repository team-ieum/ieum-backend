# Workflow Core Module Context

## 역할
워크플로우 엔진의 핵심 비즈니스 로직. 워크플로우 CRUD·버전 관리·실행 엔진·스케줄러·실행 이력을 담당한다.

## 현재 상태
구현 완료 (main 68개 클래스). 실행 엔진·이력 영속·SSE 이벤트·Quartz 스케줄러 동작 중.

## 실행 엔진 (engine/)

### SyncExecutionRuntime
이름은 Sync지만 **DAG 위상정렬 + fan-out 병렬** 실행이다.
- Kahn 위상정렬로 구조적 사이클 검출 → 사이클이면 실행 거부
- 워커 스레드 풀(`workflow.execution.parallelism`, 기본 4)에서 노드 실행, 메인 스레드가 상태/JPA 독점
- CONDITION 분기 시 선택 안 된 경로는 live 입력이 없어 자연 스킵
- 실행 자체는 `@Async` 백그라운드 스레드 (트리거는 api 모듈 `WorkflowExecutionRunner`)

### 노드 타입 (NodeType)
`TRIGGER`, `AI`, `CONDITION`, `HTTP`, `TRANSFORM` — 5종뿐. 외부 서비스(Gmail·Notion 등) 연동은 별도 노드 타입이 아니라 AI 노드의 도구/HTTP 노드로 처리한다.

### NodeExecutor
`NodeExecutor` 인터페이스 + 타입별 구현체(`AgentNodeExecutor`, `ConditionNodeExecutor`, `HttpNodeExecutor`, `TransformNodeExecutor`, `TriggerNodeExecutor`). 결과는 `ExecutorResult`.

### Provider 포트 + Stub 패턴
workflow-core는 api/auth 모듈에 의존할 수 없으므로, 필요한 기능은 **포트 인터페이스**로 선언하고 api 모듈이 구현체를 제공한다. 포트마다 `@ConditionalOnMissingBean` Stub이 있어 workflow-core 단독 테스트가 가능하다 (현재 8개).

| 포트 | 실구현 위치 |
|------|------------|
| `CredentialProvider` | api (AI 크레덴셜 복호화) |
| `GoogleTokenProvider` / `NotionTokenProvider` / `GitHubTokenProvider` | api (OAuth 토큰) |
| `WebhookCredentialProvider` | api |
| `McpCatalogProvider` | api (MCP 서버 카탈로그) |
| `UserRoleProvider` | api (self-hosted LLM 라우팅 판정) |
| `BetaPlatformProvider` | api (베타 플랫폼 키 쿼터) |

새 포트 추가 시 Stub도 같이 만들 것 — 없으면 workflow-core 테스트 컨텍스트가 깨진다.

### 변수 참조 시스템
- 문법: `{{nodes.<node_uuid>.output.<field>}}`
- `ExecutionCursor.renderVariables()`가 실행 시점에 치환. 중첩 참조 불가(1단계만)

### 실행 이벤트 (engine/event/)
`ExecutionEventPublisher` — executionId 키의 in-memory `Sinks.Many` 멀티캐스트 허브. SSE 구독과 `@Async` 실행 스레드를 연결한다.
- **단일 인스턴스 전제** — 다중 인스턴스로 가면 Redis Pub/Sub 등으로 교체 필요
- 늦은 구독 보완: `WorkflowExecutionService.loadEventSnapshot()`이 DB 로그를 이벤트로 변환해 먼저 흘림

## 영속 (domain/, repository/)

| 엔티티 | 테이블 | 비고 |
|--------|--------|------|
| `Workflow` | `workflows` | |
| `WorkflowVersion` | `workflow_versions` | 실행 시점 버전 고정 참조 |
| `WorkflowExecution` | `workflow_runs` | status, triggerType, startedAt/finishedAt |
| `WorkflowExecutionLog` | `node_runs` | nodeId, nodeType, status, input/output JSON(TEXT), errorMessage, durationMs |
| `ChatMessage`/`ChatSession` | chat/ 하위 | 워크플로우 채팅 |
| `WorkflowDefinitionDocument` | Mongo | nodes/edges 정의. PG `mongoDefinitionId`로 조인 |

enum 실제 값: `ExecutionStatus`=PENDING/RUNNING/SUCCESS/FAILED, `ExecutionLogStatus`=SUCCESS/FAILED/SKIPPED, `TriggerType`=MANUAL/WEBHOOK/SCHEDULE.

**DDL은 Flyway가 아니라 `ddl-auto: update`** — 마이그레이션 파일 없음. 컬럼 추가는 엔티티 필드만 넣으면 된다.

## 스케줄러 (scheduler/, config/)
Quartz. `WorkflowScheduler`(등록/해제), `WorkflowScheduleJob`(실행), `ScheduleJobRestorer`(부팅 시 복원), `WorkflowCleanupScheduler`, `JobKeyGenerator`.

## 주의사항
- `node_runs`의 input/output에 자격증명 원문 저장 금지 — `SyncExecutionRuntime.maskSensitiveFields()`가 `apiKey/token/secret/password/Authorization` 키를 `***`로 마스킹한다. 새 민감 키는 `SENSITIVE_KEYS`에 추가
- 실행 로그 저장 실패는 실행 전체를 중단시키지 않음(warn만) — 이력 누락 가능성이 설계상 허용됨
- 워크플로우당 트리거 노드 1개만 허용
- 노드 config에 실제 토큰/키 저장 금지 — credential_id 참조만
- `workflowVersionId`는 Mongo 조인에 쓰지 말 것(죽은 필드). Mongo `_id` ↔ PG `mongoDefinitionId`가 조인 키

## 패키지 구조
```
com.ieum.workflowcore
├── chat/          # ChatSession, ChatMessage, MessageType + repository
├── config/        # SchedulerConfig, QuartzJobFactory, WorkflowConfig
├── document/      # WorkflowDefinitionDocument (Mongo), BrandVersionCount
├── domain/        # Workflow, WorkflowVersion, WorkflowExecution, WorkflowExecutionLog + enums
├── engine/        # SyncExecutionRuntime, ExecutionCursor, ExecutionContext, Node, Edge, ExecutorResult
│   ├── event/     # ExecutionEvent, ExecutionEventPublisher, ExecutionEventSnapshot
│   └── executor/  # NodeExecutor 구현체 + Provider 포트/Stub + dto
├── repository/    # Workflow/Version/Execution/ExecutionLog Repository, WorkflowQueryRepository
├── scheduler/     # Quartz 잡·복원·정리
└── service/       # WorkflowCrudService, WorkflowExecutionService, IntegrationWorkflowQueryService
```
