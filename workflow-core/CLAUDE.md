# Workflow Core Module Context

## 역할
워크플로우 엔진의 핵심 비즈니스 로직. 워크플로우 CRUD·버전 관리·실행 엔진·스케줄러·실행 이력을 담당한다.

## 현재 상태
구현 완료 (main 79개 클래스). 실행 엔진·이력 영속·SSE 이벤트·Quartz 스케줄러 동작 중.
노드 재시도·멱등 가드·모델 fallback·실패 실행 재처리(IEUM-BE-46)까지 포함.

## 실행 엔진 (engine/)

### SyncExecutionRuntime
이름은 Sync지만 **DAG 위상정렬 + fan-out 병렬** 실행이다.
- Kahn 위상정렬로 구조적 사이클 검출 → 사이클이면 실행 거부
- 워커 스레드 풀(`workflow.execution.parallelism`, 코드 기본 4 / api `application.yml`이 3으로 낮춤)에서 노드 실행, 메인 스레드가 상태/JPA 독점
- CONDITION 분기 시 선택 안 된 경로는 live 입력이 없어 자연 스킵
- 실행 트리거는 api 모듈 `WorkflowExecutionRunner` → Redis Stream 잡 큐 → 같은 프로세스 워커 (Redis 장애 시 `@Async` 직접 실행 폴백). Quartz 스케줄 실행만 큐를 거치지 않고 런타임을 직접 부른다 — 그래서 내구성이 없다
- 실패 확정은 `finalizeFailure()` 한 곳 — 상태 전이 + SSE 종료 이벤트 + `AlertNotifier` 발신. 이미 종료된 실행은 상태·알림을 다시 건드리지 않는다(중복 알림 방지)

### 노드 재시도 (retry)
- `RetryPolicy` — 노드 config의 `retry` 객체를 파싱한 record(정책 파싱·백오프 계산·회차별 모델 선택). 사용자 편집값이라 `maxAttempts` ≤ 10, 단일 백오프 ≤ 10분으로 상·하한 강제
- `FailureKind` — 실패 원인 enum이 **재시도 가능 여부를 스스로 보유**한다(`isRetryable()`). TIMEOUT·RATE_LIMIT·SERVER_ERROR·NETWORK만 재시도 대상, CLIENT_ERROR·UNKNOWN은 아님
- `FailureClassifier` — agent errorCode / HTTP status / 예외 cause 체인 → `FailureKind`. 문자열 추론이 아니라 Executor가 실패를 반환할 때 명시적으로 채운다
- `RetryProperties`(`workflow.execution.retry.*`) — 기본값. **AI 노드만 기본 재시도(3회)**, 그 외는 1회(= 명시 선언 없으면 재시도 없음) — HTTP는 멱등성을 보장할 수 없어 기본 재시도가 위험하다
- 백오프는 지수 + **full jitter**(`[0, computed]` 균등 난수). 지터 난수는 `ThreadLocalRandom`이다 — 워커 스레드가 공유하는 필드이므로 `RandomGenerator.getDefault()`로 바꾸지 말 것(스레드 안전하지 않다)
- **대기는 워커 스레드의 `Thread.sleep`이다.** 메인 스레드 JPA 독점 구조를 유지하려 그렇게 뒀고, 그 대가로 재시도 대기가 워커 슬롯을 점유한다(fan-out이 넓으면 슬롯 고갈)

### 멱등성 (재시도 중복 호출 가드)
- `IdempotencyMode` = NONE / HEADER / MARKER / BOTH. 노드 config `retry.idempotency`로 선언. **부수효과가 있는 HTTP·AI 노드가 HEADER 기본**, 나머지는 NONE. AI 노드는 기본 재시도가 3회인데 도구를 쓰므로 NONE이면 회차마다 부수효과가 중복된다(MARKER는 재시도와 양립 불가라 HEADER가 유일한 선택지). agent가 `X-Idempotency-Key`를 소비하며(IEUM-AI-52) 소비하지 않는 배포에선 no-op이라 배포 순서 무관
- `IdempotencyKeys.generate(executionId, nodeId)` — sha256 앞 32자 hex. **attempt 번호를 절대 섞지 않는다**(섞으면 중복 차단이 성립하지 않음)
- HEADER: HTTP 노드는 `Idempotency-Key`, AI 노드는 agent에 `X-Idempotency-Key`. `policy.isDisabled()`(재시도 없음)면 붙이지 않는다
- MARKER: `IdempotencyStore` 포트로 in-flight 마커를 세우고, 마커가 있으면 **재시도를 포기**한다. 마커 해제는 재시도 루프 전체가 끝난 뒤 1회만 — attempt별 finally에서 해제하면 모드가 무력화된다
- 런타임은 MARKER 모드 노드를 attempt 1 이후 곧바로 멈춘다 — 2회차를 시작하면 원 실패 원인이 마커 차단(CLIENT_ERROR)으로 덮여써져 `node_runs` 진단과 `retryExhausted` 신호가 왜곡된다

### 모델 fallback
`RetryPolicy.modelForAttempt()` — 1회차는 원 모델, 2회차부터 `retry.modelFallback` 목록을 차례로 쓴다.
`AgentNodeExecutor`가 **platform-key 모드가 정해진 뒤에** 적용하며, 그 모드에선 `BetaPlatformProvider.isModelAllowed()`로 허용 목록을 검증한다 — platform 키는 Gemini 한 장이라 비-Gemini fallback은 agent resolve 자체가 실패한다.

### 성공 노드 스킵 (실패 실행 재처리)
`execute(version, executionId, triggerData, preCompletedOutputs)` 4-인자 오버로드. `preCompletedOutputs`에 있는 노드는 executor를 부르지 않고 주어진 출력을 성공 결과로 삼아 `SKIPPED` 로그를 남긴다. 빈 Map이면 3-인자와 완전히 동일 동작.
**TRIGGER 노드는 스킵 대상에서 제외한다** — `node_runs`의 output은 `SensitiveDataMasker`를 거쳐 민감값이 `***`인데, `TriggerNodeExecutor`는 부작용이 없어 재실행이 공짜이고 복호된 triggerData로 같은 출력을 다시 만든다.

### 노드 타입 (NodeType)
`TRIGGER`, `AI`, `CONDITION`, `HTTP`, `TRANSFORM` — 5종뿐. 외부 서비스(Gmail·Notion 등) 연동은 별도 노드 타입이 아니라 AI 노드의 도구/HTTP 노드로 처리한다.

### NodeExecutor
`NodeExecutor` 인터페이스 + 타입별 구현체(`AgentNodeExecutor`, `ConditionNodeExecutor`, `HttpNodeExecutor`, `TransformNodeExecutor`, `TriggerNodeExecutor`). 결과는 `ExecutorResult`.

3-인자 `execute(node, input, cursor)`가 기본이고, 외부 호출이 멱등 가드를 적용해야 하는 Executor(HTTP·AI)만 4-인자 오버로드 `execute(node, input, cursor, NodeAttempt)`를 override한다. `NodeAttempt(attempt, idempotencyKey, policy)`가 회차·멱등성 키·재시도 정책을 실어 온다 — **`ExecutionCursor`/`ExecutionContext`에 attempt 정보를 넣지 말 것**(워커 스레드 간 공유 객체다).

### Provider 포트 + Stub 패턴
workflow-core는 api/auth 모듈에 의존할 수 없으므로, 필요한 기능은 **포트 인터페이스**로 선언하고 api 모듈이 구현체를 제공한다. 포트마다 `@ConditionalOnMissingBean` Stub이 있어 workflow-core 단독 테스트가 가능하다 (현재 10개).

| 포트 | 실구현 위치 |
|------|------------|
| `CredentialProvider` | api (AI 크레덴셜 복호화) |
| `GoogleTokenProvider` | api (OAuth 토큰) |
| `NotionTokenProvider` | api (OAuth 토큰) |
| `GitHubTokenProvider` | api (OAuth 토큰) |
| `WebhookCredentialProvider` | api (송신 웹훅 URL + 실패 알림 대상 웹훅) |
| `McpCatalogProvider` | api (MCP 서버 카탈로그) |
| `UserRoleProvider` | api (self-hosted LLM 라우팅 판정) |
| `BetaPlatformProvider` | api (베타 플랫폼 키 쿼터 + fallback 모델 허용 판정) |
| `IdempotencyStore` | api (Redis SETNX in-flight 마커) |
| `AlertNotifier` | api (실행 실패 Discord 알림) |

새 포트 추가 시 Stub도 같이 만들 것 — 없으면 workflow-core 테스트 컨텍스트가 깨진다.
`IdempotencyStore`·`AlertNotifier` 구현체는 **저장소·발신 장애를 삼켜야 한다** — 실행 종료 처리를 깨는 것보다 중복 호출·알림 유실을 감수한다(의도적 트레이드오프).

### 변수 참조 시스템
- 문법: `{{nodes.<node_uuid>.output.<field>}}`
- `ExecutionCursor.renderVariables()`가 실행 시점에 치환. 중첩 참조 불가(1단계만)

### 실행 이벤트 (engine/event/)
`ExecutionEventPublisher` — executionId 키의 in-memory `Sinks.Many` 멀티캐스트 허브. SSE 구독(HTTP 스레드)과 실행 스레드를 **같은 JVM 안에서** 연결한다 — 그래서 api의 잡 큐 워커를 별도 프로세스로 뺄 수 없다.
- **단일 인스턴스 전제** — 다중 인스턴스로 가면 Redis Pub/Sub 등으로 교체 필요
- 늦은 구독 보완: `WorkflowExecutionService.loadEventSnapshot()`이 DB 로그를 이벤트로 변환해 먼저 흘림

## 영속 (domain/, repository/)

| 엔티티 | 테이블 | 비고 |
|--------|--------|------|
| `Workflow` | `workflows` | |
| `WorkflowVersion` | `workflow_versions` | 실행 시점 버전 고정 참조 |
| `WorkflowExecution` | `workflow_runs` | status, triggerType, startedAt/finishedAt, traceId, `retry_exhausted`, `trigger_data`, `retried_by_execution_id`, `error_message` |
| `WorkflowExecutionLog` | `node_runs` | nodeId, nodeType, status, input/output JSON(TEXT), errorMessage, durationMs, traceId, prompt/completion/total tokens, `attempt_count` |
| `ChatMessage`/`ChatSession` | chat/ 하위 | 워크플로우 채팅 |
| `WorkflowDefinitionDocument` | Mongo | nodes/edges 정의. PG `mongoDefinitionId`로 조인 |

enum 실제 값: `ExecutionStatus`=PENDING/RUNNING/SUCCESS/FAILED, `ExecutionLogStatus`=SUCCESS/FAILED/SKIPPED, `TriggerType`=MANUAL/WEBHOOK/SCHEDULE.

새 컬럼 (IEUM-BE-46):
- `node_runs.attempt_count` — 결과가 나오기까지의 시도 횟수. 재시도 없이 끝나면 1, **`SKIPPED`(재처리 스킵)은 0**
- `workflow_runs.retry_exhausted` — 재시도 대상 실패로 `maxAttempts`를 다 쓰고도 실패했는지. 판정은 런타임이 하고 컬럼엔 결과만 남는다
- `workflow_runs.trigger_data` — 트리거가 전달한 초기 입력. **AES-256 암호문(TEXT)이다 — 직접 파싱하지 말 것.** 복호는 `WorkflowExecutionService.decryptTriggerData()` 하나뿐이다(잡 큐 워커·재처리가 공유). 조회 API 응답에 그대로 노출 금지
- `workflow_runs.retried_by_execution_id` — 이 실행을 재처리하려 만든 새 실행. 역방향(재처리 → 원본) 조회는 `findByRetriedByExecutionId`

새 컬럼 (IEUM-BE-50):
- `workflow_runs.error_message` — **실행 단위** 실패 사유. 노드 로그(`node_runs.error_message`)가 남지 않은 실패(런타임 진입 전 실패, 고립 실행 sweeper 확정)에서 유일한 원인 기록이라 `markAsFailed`만 채운다. 노드가 특정된 실패는 여기가 null이고 노드 로그에 원인이 있다. 대시보드 에러 목록이 "노드 로그 → 이 컬럼 → 기본 문구" 순으로 고른다

**DDL은 Flyway가 아니라 `ddl-auto: update`** — 마이그레이션 파일 없음. 컬럼 추가는 엔티티 필드만 넣으면 된다.
단, **`nullable = false` 컬럼을 기존 행이 있는 테이블에 추가할 때는 `@ColumnDefault`가 반드시 필요하다.** 없으면 PostgreSQL이 DDL을 거부하는데 `ddl-auto: update`는 그 오류를 경고로만 남기고 부팅을 계속해 **컬럼 없이 앱이 뜬다.** 테스트는 `ddl-auto: create-drop`(빈 스키마)이라 이 사고를 잡지 못한다 — 이번에 `retry_exhausted`·`alert_target`이 걸릴 뻔했다.

## 스케줄러 (scheduler/, config/)
Quartz. `WorkflowScheduler`(등록/해제), `WorkflowScheduleJob`(실행), `ScheduleJobRestorer`(부팅 시 복원), `WorkflowCleanupScheduler`, `JobKeyGenerator`.
`WorkflowScheduleJob`은 `SyncExecutionRuntime`을 직접 부른다 — api의 잡 큐를 거치지 않아 **스케줄 실행에는 내구성이 없다**(프로세스가 죽으면 유실). 해결 경로는 `boolean enqueue(UUID)` Provider 포트지만 별도 이슈다.

`WorkflowCleanupScheduler`는 두 가지를 돈다(둘 다 `@Scheduled` cron, Quartz 아님):
- `cleanupOrphanWorkflows()` — 고아 빈 워크플로우 hard delete
- `failStuckRunningExecutions()` — **고립 `RUNNING` 실행 sweeper**(IEUM-BE-50). 프로세스가 죽어 런타임이 종료 처리를 못 한 실행은 영원히 `RUNNING`으로 남고, 재처리 API가 `FAILED`만 받으므로 다시 돌릴 수 없다. 재처리로 생긴 실행이 이렇게 굳으면 원본까지 "재처리 진행 중"으로 판정돼 **영구히 막힌다.** 확정은 `WorkflowExecutionService.markAsFailed()`에 맡긴다 — 런타임 밖 실패 확정 경로가 이미 종료 상태 가드·알림·커밋 후 발신을 갖췄다. 런타임의 `finalizeFailure`는 실행 중 인스턴스 상태를 쥔 private 경로라 타지 않는다. `retryExhausted`는 false(재시도 소진이 아니라 재시도 판정 자체가 못 이뤄진 실패)

## 설정 (workflow-core가 읽는 키)
| 키 | 기본값 | 용도 |
|----|--------|------|
| `workflow.execution.parallelism` | 4 (api yml이 3으로 덮음) | 워크플로우 1개 내부 fan-out 병렬도 |
| `workflow.execution.retry.ai-max-attempts` | 3 | AI 노드 기본 시도 횟수(최초 포함) |
| `workflow.execution.retry.default-max-attempts` | 1 | AI 외 노드 기본 시도 횟수 (1 = 재시도 없음) |
| `workflow.execution.retry.backoff-ms` | 1000 | 첫 재시도 전 대기 |
| `workflow.execution.retry.multiplier` | 2.0 | 회차당 대기 증가 배수 |
| `workflow.execution.retry.max-backoff-ms` | 30000 | 단일 대기 상한 |
| `workflow.execution.retry.jitter` | true | full jitter 적용 여부 |
| `workflow.execution.stuck.threshold` | `PT2H` | 이 시간 넘게 `RUNNING`인 실행을 고립으로 보고 `FAILED`로 확정. **내리지 말 것** — 잡 큐 회수(`reclaim-min-idle` 10분)로 복구될 실행을 먼저 FAILED로 굳히면 워커가 종료 상태로 보고 건너뛰어 복구가 취소된다 |

`retry.*`는 `RetryProperties`(`@ConfigurationProperties`)의 필드 기본값이고 yml에 선언돼 있지 않다 — 장애 시 `ai-max-attempts: 1`로 재시도를 전역으로 끌 수 있게 코드 상수가 아니라 설정으로 뒀다. 노드 config의 `retry` 선언이 이 기본값보다 우선한다.

## 주의사항
- `node_runs`의 input/output에 자격증명 원문 저장 금지 — `SensitiveDataMasker.mask()`(util/)가 `apiKey/api_key/token/secret/password/Authorization` 키를 `***`로 마스킹한다. 새 민감 키는 `SENSITIVE_KEYS`에 추가. **중첩 Map·List 내부까지 재귀 적용된다** (IEUM-BE-62에서 확장 — 그 전에는 최상위 키만 검사했다). `workflow_runs.trigger_data`는 마스킹이 아니라 AES-256 암호화다 — `mask()`의 호출부는 `SyncExecutionRuntime` 하나뿐이다. 같은 클래스의 `containsWebhookUrl()`은 api `WorkflowService`가 노드 `config.url` 원문 웹훅 저장을 거부할 때 쓴다 — **웹훅 도메인 정규식은 `WEBHOOK_URL_PATTERN` 한 곳뿐이어야 한다**(마스킹과 저장 거부가 갈라지면 한쪽만 고쳐진다)
- 실행 로그 저장 실패는 실행 전체를 중단시키지 않음(warn만) — 이력 누락 가능성이 설계상 허용됨
- 워크플로우당 트리거 노드 1개만 허용
- 노드 config에 실제 토큰/키 저장 금지 — credential_id 참조만
- `workflowVersionId`는 Mongo 조인에 쓰지 말 것(죽은 필드). Mongo `_id` ↔ PG `mongoDefinitionId`가 조인 키

## 패키지 구조
```
com.ieum.workflowcore
├── chat/          # ChatSession, ChatMessage, MessageType + repository
├── config/        # SchedulerConfig, QuartzJobFactory, WorkflowConfig, RetryProperties
├── document/      # WorkflowDefinitionDocument (Mongo), BrandVersionCount
├── domain/        # Workflow, WorkflowVersion, WorkflowExecution, WorkflowExecutionLog + enums
├── engine/        # SyncExecutionRuntime, ExecutionCursor, ExecutionContext, Node, Edge, ExecutorResult
│   │              # + RetryPolicy, FailureKind, FailureClassifier, IdempotencyMode, IdempotencyKeys
│   ├── event/     # ExecutionEvent, ExecutionEventPublisher, ExecutionEventSnapshot
│   └── executor/  # NodeExecutor(+NodeAttempt) 구현체 + Provider 포트/Stub + dto
├── repository/    # Workflow/Version/Execution/ExecutionLog Repository, WorkflowQueryRepository
├── scheduler/     # Quartz 잡·복원·정리
├── service/       # WorkflowCrudService, WorkflowExecutionService, IntegrationWorkflowQueryService
└── util/          # SensitiveDataMasker
```
