# Workflow Core Module Context

## 역할
워크플로우 엔진의 핵심 비즈니스 로직. 워크플로우 CRUD·버전 관리·실행 엔진·스케줄러·실행 이력을 담당한다.

## 현재 상태
구현 완료 (main 88개 클래스). 실행 엔진·이력 영속·SSE 이벤트·Quartz 스케줄러 동작 중.
노드 재시도·멱등 가드·모델 fallback·실패 실행 재처리(IEUM-BE-46)까지 포함.

## 실행 엔진 (engine/)

### SyncExecutionRuntime
이름은 Sync지만 **DAG 위상정렬 + fan-out 병렬** 실행이다.
- Kahn 위상정렬로 구조적 사이클 검출 → 사이클이면 실행 거부
- 워커 스레드 풀(`workflow.execution.parallelism`, 코드 기본 4 / api `application.yml`이 3으로 낮춤)에서 노드 실행, 메인 스레드가 상태/JPA 독점
- CONDITION 분기 시 선택 안 된 경로는 live 입력이 없어 자연 스킵
- 실행 트리거는 api 모듈 `WorkflowExecutionRunner` → Redis Stream 잡 큐 → 같은 프로세스 워커 (Redis 장애 시 `@Async` 직접 실행 폴백). Quartz 스케줄 실행도 `ExecutionJobEnqueuer` 포트로 같은 큐를 탄다(IEUM-BE-51)
- 실패 확정은 `finalizeFailure()` 한 곳 — 상태 전이 + SSE 종료 이벤트 + `AlertNotifier` 발신. 이미 종료된 실행은 상태·알림을 다시 건드리지 않는다(중복 알림 방지)
- **정적 검증(1단계)** — Mongo 정의 로드·TRIGGER 확인 직후, `startIfNotTerminal` 전에 `engine/validation/WorkflowValidationClient`(agent `POST /v1/workflows/validate`, 3초)로 검사한다. 응답 `errors`가 비어 있지 않을 때만 `WorkflowValidationException`(`"검증 실패 N건: <첫 message>"`)으로 노드 0개 FAILED. agent 장애·타임아웃·4xx·5xx·파싱 실패는 `Optional.empty()` → 검사 없이 진행(fail-open). 스케줄·웹훅·앱 트리거·재처리·승인 이어진 실행·큐 전부 이 자리를 지나고, 노드 단일 테스트(`NodeTestRunner`)는 지나지 않는다. 1단계 실패는 `recordErrorIfNotTerminal`로 `workflow_runs.error_message`에 사유를 남긴다(러너의 `markAsFailed`는 이미 FAILED라 no-op)

### 노드 재시도 (retry)
- `RetryPolicy` — 노드 config의 `retry` 객체를 파싱한 record(정책 파싱·백오프 계산·회차별 모델 선택). 사용자 편집값이라 `maxAttempts` ≤ 10, 단일 백오프 ≤ 10분으로 상·하한 강제
- `FailureKind` — 실패 원인 enum이 **재시도 가능 여부를 스스로 보유**한다(`isRetryable()`). TIMEOUT·RATE_LIMIT·SERVER_ERROR·NETWORK만 재시도 대상, CLIENT_ERROR·UNKNOWN은 아님
- `FailureClassifier` — agent errorCode / HTTP status / 예외 cause 체인 → `FailureKind`. 문자열 추론이 아니라 Executor가 실패를 반환할 때 명시적으로 채운다
- **`retry.timeoutMs`는 호출 타임아웃이 아니다** — MARKER in-flight 마커 TTL(`timeoutMs`+30초, 미선언 5분) 산정 전용(`NodeExecutor.markerTtl()`). 실제 호출 타임아웃은 AI·ACTION 노드=agent 호출(`ieum.agent.timeout-seconds`, api yml 180초), HTTP 노드=공유 RestTemplate read 30초(`ieum.http.read-timeout-seconds`)이고 노드별로 못 바꾼다. 의도적으로 배선하지 않았다(BE-55) — BE가 먼저 끊어도 agent는 도구를 계속 실행해 실패 기록 뒤에 부수효과가 남고, 같은 키 재시도는 `DUPLICATE_REQUEST`를 받는다
- `RetryProperties`(`workflow.execution.retry.*`) — 기본값. **AI 노드만 기본 재시도(3회)**, 그 외는 1회(= 명시 선언 없으면 재시도 없음) — HTTP는 멱등성을 보장할 수 없어 기본 재시도가 위험하다
- 백오프는 지수 + **full jitter**(`[0, computed]` 균등 난수). 지터 난수는 `ThreadLocalRandom`이다 — 워커 스레드가 공유하는 필드이므로 `RandomGenerator.getDefault()`로 바꾸지 말 것(스레드 안전하지 않다)
- **대기는 워커 스레드의 `Thread.sleep`이다.** 메인 스레드 JPA 독점 구조를 유지하려 그렇게 뒀고, 그 대가로 재시도 대기가 워커 슬롯을 점유한다(fan-out이 넓으면 슬롯 고갈)

### 멱등성 (재시도 중복 호출 가드)
- `IdempotencyMode` = NONE / HEADER / MARKER / BOTH. 노드 config `retry.idempotency`로 선언. **부수효과가 있는 HTTP·AI·ACTION 노드가 HEADER 기본**, 나머지는 NONE. AI 노드는 기본 재시도가 3회인데 도구를 쓰므로 NONE이면 회차마다 부수효과가 중복된다(MARKER는 재시도와 양립 불가라 HEADER가 유일한 선택지). agent가 `X-Idempotency-Key`를 소비하며(IEUM-AI-52) 소비하지 않는 배포에선 no-op이라 배포 순서 무관
- `IdempotencyKeys.generate(executionId, nodeId)` — sha256 앞 32자 hex. **attempt 번호를 절대 섞지 않는다**(섞으면 중복 차단이 성립하지 않음)
- HEADER: HTTP 노드는 `Idempotency-Key`, AI·ACTION 노드는 agent에 `X-Idempotency-Key`. `policy.isDisabled()`(재시도 없음)면 붙이지 않는다
- MARKER: `IdempotencyStore` 포트로 in-flight 마커를 세우고, 마커가 있으면 **재시도를 포기**한다. 마커 해제는 재시도 루프 전체가 끝난 뒤 1회만 — attempt별 finally에서 해제하면 모드가 무력화된다. **같은 실행 안의 재시도만 막는다** — 프로세스 크래시 후 큐 회수 재배달(같은 executionId → 같은 키)은 마커 TTL(5분)과 `reclaim-min-idle`(10분) 타이밍에 달려 막지 못할 수 있다(BE-53). 기본 TTL은 일부러 안 올렸다 — 재실행이 그 노드에 닿는 시점에 상한이 없어 올려도 보장이 안 된다
- 런타임은 MARKER 모드 노드를 attempt 1 이후 곧바로 멈춘다 — 2회차를 시작하면 원 실패 원인이 마커 차단(CLIENT_ERROR)으로 덮여써져 `node_runs` 진단과 `retryExhausted` 신호가 왜곡된다

### 모델 fallback
`RetryPolicy.modelForAttempt()` — 1회차는 원 모델, 2회차부터 `retry.modelFallback` 목록을 차례로 쓴다.
`AgentNodeExecutor`가 **platform-key 모드가 정해진 뒤에** 적용하며, 그 모드에선 `BetaPlatformProvider.isModelAllowed()`로 허용 목록을 검증한다 — platform 모드에서 agent는 노드 model을 무시하고 provider 기본 모델로 강등하므로(agent `core/agent.py`) 요청이 깨지진 않지만, 허용 목록 밖 fallback을 BE에서 먼저 거르는 이중 방어다.

### 성공 노드 스킵 (실패 실행 재처리)
`execute(version, executionId, triggerData, preCompletedOutputs)` 4-인자 오버로드. `preCompletedOutputs`에 있는 노드는 executor를 부르지 않고 주어진 출력을 성공 결과로 삼아 `SKIPPED` 로그를 남긴다. 빈 Map이면 3-인자와 완전히 동일 동작.
**TRIGGER 노드는 스킵 대상에서 제외한다** — `node_runs`의 output은 `SensitiveDataMasker`를 거쳐 민감값이 `***`인데, `TriggerNodeExecutor`는 부작용이 없어 재실행이 공짜이고 복호된 triggerData로 같은 출력을 다시 만든다.
**재사용 체인 거슬러 가기** — `WorkflowExecutionService.loadReusableNodeOutputs`는 직전 실행(`findByRetriedByExecutionId`)의 SUCCESS·SKIPPED 행이 0건이면(정적 검증 등 1단계에서 막힌 이어진 실행·재처리) 그 실행의 원본으로 한 단계씩 더 거슬러 가 첫 비어 있지 않은 실행의 행을 쓴다(방문 집합으로 순환 차단). 막힌 실행을 재처리해도 앞 실행의 성공 노드·승인 게이트를 다시 돌리지 않는다 — 거부 게이트 FAILED 행은 여전히 재사용 대상이 아니다.

### 승인 게이트 (HITL, IEUM-BE-45)
`APPROVAL` 노드는 NodeExecutor가 없다 — `dispatch()`가 사전 완료 출력(`preCompleted`)이 없는 APPROVAL을 **제출하지 않고** 대기 목록에 넣고 `APPROVAL_REQUESTED`를 발행한다. 제출하지 않으니 `propagate`가 불리지 않아 하류의 남은 입력 수가 줄지 않는다 — 미승인 게이트의 하류는 이 실행에서 절대 ready가 되지 않는다. 게이트와 무관한 분기는 계속 돈다.
- 루프가 끝나면: 실패 있음 → `FAILED`(실패가 대기보다 우선) / 대기 게이트 있음 → `pauseForApproval` 조건부 UPDATE(`RUNNING`일 때만)로 `WAITING_APPROVAL` + `EXECUTION_COMPLETED(WAITING_APPROVAL)` / 둘 다 없음 → `SUCCESS`. 0행이면 `publishAlreadyFinished`. **실패 알림 없음**
- `dispatch()`에서 사전 완료 검사가 executor 조회보다 **먼저**다 — 승인 뒤 이어진 실행에서 게이트는 재처리 스킵 경로(SKIPPED)로 통과한다. 순서를 뒤집으면 APPROVAL에서 "NodeExecutor 없음"이 난다. `execute()`의 executor 사전 검증도 APPROVAL만 뺀다
- CONDITION 죽은 분기의 게이트는 dispatch되지 않아 대기에 들지 않는다. 그래서 대기 집합을 그래프에서 역산하지 않고 `waiting_approval_node_ids`에 저장한다
- 승인(api `ExecutionApprovalService`) = 게이트마다 `node_runs` SUCCESS 행(출력 `{approved, approvedBy, approvedAt}`, `WorkflowExecutionService.recordApprovalDecision`) + 원 실행 SUCCESS + 재처리와 같은 경로로 이어진 새 실행(`retriedBy` 링크). 거부 = 게이트 FAILED 행 + 원 실행 FAILED(`error_message = "승인 거부: <사유>"`, 사유 없으면 `"승인 거부"`), 알림 없음. 만료 = sweeper가 `markAsFailed(id, "승인 만료", WAITING_APPROVAL)`, 알림 있음
- 거부된 실행의 게이트 FAILED 행은 재사용 대상(SUCCESS·SKIPPED)이 아니고 만료된 실행엔 게이트 행 자체가 없어, 재처리해도 게이트가 다시 멈춘다 — 우회 경로 없음
- `WAITING_APPROVAL`은 런타임·워커 입장에서 종료 상태다: `startIfNotTerminal`·`finishIfNotTerminal`·`ExecutionJobWorker`·`loadEventSnapshot`이 전부 종료로 취급한다. 2-인자 `markAsFailed`(고립 sweeper·워커·러너)도 `PENDING`·`RUNNING`만 끝내고 대기는 건드리지 않는다 — **대기를 끝내는 건 승인·거부·만료뿐이다**

### 노드 단일 테스트 (NodeTestRunner, service/)
노드 하나를 `SyncExecutionRuntime` 밖에서 실행하고 `node_test_samples`에 최신 1건으로 upsert한다 — 워크플로우 JSON·`workflow_runs`·`node_runs`엔 쓰지 않는다. 전 타입 — APPROVAL은 실행기 없이 런타임 승인 출력 모양(`{approved: true, approvedBy: 소유자 ID, approvedAt}`)의 SUCCESS 샘플만 저장한다(하류 참조 테스트용).
- 실행기는 `List<NodeExecutor>`에서 타입으로 골라 직접 부른다. 실행기가 읽는 커서 상태는 userId·traceId·nodeOutputs와 치환 메서드뿐이라 테스트용 `ExecutionContext`·`ExecutionCursor(allNodes=[노드], allEdges=[])`로 충분하다. AI 쿼터 예약·토큰 차감은 실행기 안에 있어 그대로 적용되고 **쓰기 액션은 실제로 실행된다**
- **단일 시도** — 재시도·모델 fallback 없음. `NodeAttempt(1, "test-<uuid>:<nodeId>", RetryPolicy.from(...))`, MARKER 모드면 러너가 `clearInFlight`
- config가 **직접 참조한** 노드의 SUCCESS 샘플만 `nodeOutputs`에 넣는다(`ExecutionCursor.referencedNodeIds`). 하나라도 없으면 `TestSampleMissingException`(400, `missingNodeIds`). 실패(FAILED) 샘플은 참조 해석에 쓰지 않고, **실패한 재테스트는 이전 SUCCESS 샘플을 덮어쓴다**
- 샘플 출력은 저장 직전 마스킹 — 하류가 `token`·`apiKey` 같은 필드를 참조하면 `***`가 들어간다(재처리 `loadReusableNodeOutputs`와 같은 트레이드오프)
- 트리거: 페이로드를 트리거 출력으로 먼저 심고 `renderDeep(config)`를 `TriggerNodeExecutor`에 넘긴 출력이 샘플이다(런타임과 같은 모양 — WEBHOOK·앱 트리거는 페이로드 그대로, MANUAL은 페이로드 필드가 config 자기 참조로만 남는다). webhook 수신은 `recordWebhookSample`(저장 최신 버전의 노드, 없으면 합성 노드 `event: webhook.catch`·config `{}`)
- upsert는 find → record → saveAndFlush, UNIQUE 경합(`DataIntegrityViolationException`)이면 한 번 더 갱신으로 쓴다
- 트랜잭션을 걸지 않는다(외부 호출을 한 트랜잭션에 묶지 않기 위해, 저장소 호출은 각자 트랜잭션. OSIV 기본 on이라 커넥션은 요청 끝까지 유지됨)

### 노드 타입 (NodeType)
`TRIGGER`, `AI`, `CONDITION`, `HTTP`, `TRANSFORM`, `APPROVAL`, `ACTION` — 7종. 노드 종류의 진실원은 `Node.event`(카탈로그 이벤트 이름 `앱.이벤트`, `EventCatalog`)이고 `type`은 저장 때 event의 `node_type`으로 계산된 값이다. `ACTION`은 앱 도구 하나를 LLM 없이 실행하는 결정론적 노드다(config는 입력 칸 평면 `config.<칸>` + 서버가 쓰는 `brand`, 실행 도구는 노드 `event`의 카탈로그 `tool_key` — `tools`·`serviceType` 없음). 그 외 외부 서비스(Gmail·Notion 등) 연동은 AI 노드의 도구/HTTP 노드로 처리한다. `APPROVAL`은 NodeExecutor가 없다(위 "승인 게이트").

### NodeExecutor
`NodeExecutor` 인터페이스 + 타입별 구현체(`AgentNodeExecutor`, `ActionNodeExecutor`, `ConditionNodeExecutor`, `HttpNodeExecutor`, `TransformNodeExecutor`, `TriggerNodeExecutor`). 결과는 `ExecutorResult`. 트리거 종류는 노드 event → `EventCatalog.triggerTypeOf`(없으면 MANUAL). WEBHOOK·앱 트리거는 심어 둔 페이로드를 그대로 출력한다(config 에코 아님 — config `{}`인 WEBHOOK도 페이로드).

**`HttpNodeExecutor`**(`HTTP`) — SSRF 경계(IEUM-BE-106): api의 전용 빈 `httpNodeRestTemplate`(`@Qualifier`, 공용 `restTemplate`은 `@Primary`)을 쓴다 — 리다이렉트를 따라가지 않고(3xx일 때만 그대로 결과 + `location` 칸 — `Location` 헤더 원문 문자열, URI로 파싱하지 않는다) 응답 본문은 `MAX_RESPONSE_BYTES`(1MB)에서 잘린다(표시 칸 없음 — JSON이 잘리면 문자열로 나간다). `validateUrl`은 첫 호스트를 한 번 조회해 루프백·사설·링크로컬·any·CGNAT `100.64.0.0/10`·ULA `fc00::/7`을 막는다. DNS 재조회(rebinding)는 막지 않는다(운영 IMDSv2·hop limit 1로 갈음). 공용 클라이언트로 되돌리지 말 것 — 302 한 번으로 내부망·메타데이터 본문이 테스트 결과로 돌아온다(api `IeumApplicationTest`가 주입 빈이 `httpNodeRestTemplate`인지 단언한다).

**`AgentNodeExecutor`**(`AI`) — `/v1/execute` body `workflowContext` = `{nodes:{<id>:{output, status:"COMPLETED", type}}, trigger:<TRIGGER 노드 출력>}`(agent `tools/workflow_context.py` 계약, 평평한 `{<id>:output}` 아님). `nodeOutputs`를 감싼 것이라 성공·재사용 노드만 있고, trigger는 원 페이로드가 아니라 트리거 노드 출력이다. 노드 테스트에선 직접 참조한 샘플만 들어가 type은 null, trigger는 없다. 요청에 `outputFields`(노드 `config.outputFields` 원값 — 캐스트·검사 없음, 검사는 agent `INVALID_OUTPUT_FIELDS`)를 싣는다. 응답 `output`은 `Object`: Map이면 그 칸이 곧 노드 출력(`{output, metadata}` 래퍼 없음), 문자열이면 `{output: 문자열}`, null이면 `{output: null}`. 칸 선언 노드(비어 있지 않은 목록)인데 문자열·null이면 CLIENT_ERROR(재시도 없음 — 옛 agent는 결정적이고 멱등 캐시로 같은 응답을 되풀이한다) — 롤백된 agent의 문장이 하류에서 `""`로 조용히 분기하는 걸 막는다. 배열·숫자·불은 agent 계약 버그라 CLIENT_ERROR. 가드 실패는 agent 실패 응답과 같이 베타 일일 호출 환불·토큰 차감 없음. 실패 응답 usage도 `ExecutorResult`에 실린다.
**`ActionNodeExecutor`**(`ACTION`) — 노드 `event` → `EventCatalog`의 `tool_key`로 합성 도구 `{name: tool_key, config: 노드 config에서 brand 제외}` 하나를 만들어 `ToolCallPreparer`로 전처리(Google 토큰·Notion/GitHub 헤더·slack/discord `webhookCredentialId` → `webhook_url`)한 뒤 ieum-agent `POST ${ieum.agent.url}/v1/actions/execute`에 위임한다(body `{nodeId, toolKey, config}` = `ActionNodeRequest`, 응답 `ActionExecutionResult{success, output(dict), errorMessage, errorCode}`). 노드 출력은 agent `output` dict 그대로(AI 노드 객체 출력과 같은 모양). LLM 헤더(`X-LLM-*`·`X-Key-Mode`)·쿼터·토큰 차감·모델 fallback이 **없다**(과금 0). 헤더는 `X-User-Id`·`X-User-Role`·`X-Node-Id`·`X-Trace-Id`·`X-Idempotency-Key`(HEADER이고 재시도가 켜졌을 때)·`X-Google-Access-Token`·도구 인증 헤더. 실패 분류: `ACTION_TOOL_FAILED`→CLIENT_ERROR(재시도 안 함), 알 수 없는 toolKey(HTTP 400)→`AGENT_BAD_REQUEST`→CLIENT_ERROR, 5xx·429·타임아웃은 재시도 대상. 쓸 수 없는 정의(event 없음·카탈로그에 없음·ACTION 아님·tool_key 없음)는 agent 호출 없이 CLIENT_ERROR "ACTION 노드에 카탈로그 ACTION event가 필요합니다.". ACTION BYOK(`tools[].auth`·`credentialId`)는 없다.
**`ToolCallPreparer`**(AI·ACTION 공유) — `tools` 참조식 치환(`renderDeep` 복사본)·Google 토큰·`ToolAuthResolver` 인증 헤더(Slack 봇 도구 `builtin:slack_*`는 BYOK 분기 없이 `X-Slack-Token` — 웹훅 도구 `slack`은 해당 없음. Discord 봇 도구 `builtin:discord_*`는 `X-Discord-Token`·`X-Discord-Guild-Ids`(연결 서버 ID 쉼표 구분) — 웹훅 도구 `discord`는 해당 없음)·slack/discord 웹훅 URL 주입. 웹훅 URL 원문은 이 복사본에만 있고 노드 원본·`node_runs` 입력에 남지 않는다. MCP 서버 해석은 AI 노드 전용이라 `AgentNodeExecutor`에 남아 있다. HTTP 상태→errorCode 합성은 `FailureClassifier.agentServiceErrorCode`.
**`ConditionNodeExecutor`**(`CONDITION`) — `equals`·`notEquals`는 문자열 비교다(둘 다 null이면 같음, 한쪽만 null이면 다름, 그 밖은 `String.valueOf` 일치). 참조식 치환 결과는 항상 문자열이라 우변을 JSON `true`·`4`로 저장해도 `"true"`·`"4"`와 같다. 대소문자·숫자 표기(`4`≠`4.0`)는 구분한다 — AI 구조화 출력의 number 칸은 agent가 정수값을 `int`로 정규화해 보낸다.

3-인자 `execute(node, input, cursor)`가 기본이고, 외부 호출이 멱등 가드를 적용해야 하는 Executor(HTTP·AI·ACTION)만 4-인자 오버로드 `execute(node, input, cursor, NodeAttempt)`를 override한다. `NodeAttempt(attempt, idempotencyKey, policy)`가 회차·멱등성 키·재시도 정책을 실어 온다 — **`ExecutionCursor`/`ExecutionContext`에 attempt 정보를 넣지 말 것**(워커 스레드 간 공유 객체다).

### Provider 포트 + Stub 패턴
workflow-core는 api/auth 모듈에 의존할 수 없으므로, 필요한 기능은 **포트 인터페이스**로 선언하고 api 모듈이 구현체를 제공한다. 포트마다 `@ConditionalOnMissingBean` Stub이 있어 workflow-core 단독 테스트가 가능하다 (현재 13개).

| 포트 | 실구현 위치 |
|------|------------|
| `CredentialProvider` | api (AI 크레덴셜 복호화) |
| `GoogleTokenProvider` | api (OAuth 토큰) |
| `NotionTokenProvider` | api (OAuth 토큰) |
| `GitHubTokenProvider` | api (OAuth 토큰) |
| `SlackTokenProvider` | api (Slack 봇 토큰 — 미연결이면 `SLACK_NOT_CONNECTED` 예외) |
| `DiscordAccessProvider` | api (Discord 봇 토큰 + 사용자 연결 서버 ID — 링크 0이면 `DISCORD_NOT_CONNECTED` 예외) |
| `WebhookCredentialProvider` | api (송신 웹훅 URL + 실패 알림 대상 웹훅) |
| `McpCatalogProvider` | api (MCP 서버 카탈로그) |
| `UserRoleProvider` | api (self-hosted LLM 라우팅 판정) |
| `BetaPlatformProvider` | api (베타 플랫폼 키 쿼터 + fallback 모델 허용 판정) |
| `IdempotencyStore` | api (Redis SETNX in-flight 마커) |
| `AlertNotifier` | api (실행 실패 Discord 알림) |
| `ExecutionJobEnqueuer` | api (스케줄 실행을 Redis Stream 잡 큐에 투입) |

새 포트 추가 시 Stub도 같이 만들 것 — 없으면 workflow-core 테스트 컨텍스트가 깨진다.
`IdempotencyStore`·`AlertNotifier` 구현체는 **저장소·발신 장애를 삼켜야 한다** — 실행 종료 처리를 깨는 것보다 중복 호출·알림 유실을 감수한다(의도적 트레이드오프).

### 변수 참조 시스템
- 문법: `{{nodes.<node_uuid>.output.<field>}}`
- `ExecutionCursor.renderVariables()`가 실행 시점에 치환. **단일 패스다** — 치환 결과(상류 데이터)에 든 `{{nodes…}}` 리터럴은 다시 치환하지 않고 그대로 둔다. 재치환하면 외부 텍스트(이슈 제목·메일 본문)가 같은 실행의 다른 노드 출력을 끌어와 이슈·메시지로 내보낸다(ACTION은 LLM 없이 그대로 넘긴다). 실행기는 `input`이 아니라 `node.getConfig()`를 직접 치환한다(`input`은 실행 로그용). AI 노드는 `prompt`와 `tools[]` 전체 문자열 값을 치환하며, `tools`는 `ExecutionCursor.renderDeep()`(재귀, 가변 복사본 — 런타임의 `input` 생성도 같은 함수)으로 치환해 노드 원본을 건드리지 않는다 — `ToolCallPreparer`가 웹훅 URL 원문을 넣는 자리라서. 미해결 참조는 `""`. **경로의 숫자 세그먼트는 List 인덱스다**(`issues.0.title`, 범위 밖·int 초과는 `""`) — 최종 값이 Map·List면 `{a=b}`가 아니라 JSON 문자열이고, 문자열·숫자·불리언은 `String.valueOf`다(결과는 항상 String). **HTTP 노드 body는 치환한 뒤 직렬화한다**(`objectMapper.writeValueAsString(cursor.renderDeep(body))`) — 직렬화 뒤에 치환하면 JSON 참조·따옴표가 body를 깨뜨린다. `renderDeep`은 Map **키**를 치환하지 않는다

### 실행 이벤트 (engine/event/)
`ExecutionEventPublisher` — executionId 키의 in-memory `Sinks.Many` 멀티캐스트 허브. SSE 구독(HTTP 스레드)과 실행 스레드를 **같은 JVM 안에서** 연결한다 — 그래서 api의 잡 큐 워커를 별도 프로세스로 뺄 수 없다.
- **단일 인스턴스 전제** — 다중 인스턴스로 가면 Redis Pub/Sub 등으로 교체 필요
- 늦은 구독 보완: `WorkflowExecutionService.loadEventSnapshot()`이 DB 로그를 이벤트로 변환해 먼저 흘림
- `ExecutionEvent`는 모든 이벤트에 `type`·`executionId`·`workflowId`·`occurredAt`(ISO-8601)을 싣는다. **`type` 키 이름은 프론트가 읽고 있어 바꾸면 깨진다.** `workflowId`는 지역 변수/파라미터로만 흐른다 — `ExecutionCursor`·`ExecutionContext`에 넣지 말 것
- `status` 필드의 enum은 SSE 전용 `NodeEventStatus`(PENDING/RUNNING/SUCCESS/FAILED/SKIPPED)다. 영속용 `ExecutionLogStatus`와 **분리**돼 있고, 이름이 겹치는 값은 직렬화 문자열이 같아야 한다(`ExecutionEventSerializationTest`가 검증). 진행 상태가 필요하다고 `ExecutionLogStatus`에 값을 더하면 `node_runs.status` 의미가 오염된다
- 스냅샷 재생 이벤트는 `ExecutionEvent.withOccurredAt()`으로 기록 시각(`node_runs.created_at`·`workflow_runs.finished_at`)을 되씌운다
- 건너뛴 노드는 `ExecutionEvent.nodeSkipped()` — **`type`은 `NODE_COMPLETED`이고 `status`만 `SKIPPED`다.** 새 `type` 값을 만들면 이미 배포된 프론트(`nodeId + type` 멱등 처리)가 그 노드의 종료를 못 받는다. 죽은 조건 분기 스킵과 재처리 스킵 둘 다 이 모양으로 나간다
- **죽은 조건 분기로 건너뛴 노드도 `node_runs`에 `SKIPPED` 행을 남긴다**(`saveSkippedLog`, output/input 없음·`attempt_count`=0). 재생은 `node_runs`만 읽으므로 행이 없으면 늦게 구독한 화면이 그 노드를 재현하지 못한다. output을 채우면 `loadReusableNodeOutputs`가 재사용 대상으로 읽어 버리니 비워 둘 것
- 라이브·재생 모양 일치는 `LiveAndSnapshotEventParityTest`가 실제 실행 산출물(`node_runs` 행)을 스냅샷 조회에 물려 검증한다. 다만 **실패로 중단된 실행의 드레인 노드**는 로그만 남고 이벤트가 없다(기존 동작, 이 테스트 범위 밖)
- 승인 게이트는 `APPROVAL_REQUESTED`(`nodeType: APPROVAL`, `status: PENDING`) → `EXECUTION_COMPLETED(WAITING_APPROVAL)`. 게이트엔 `node_runs` 행이 없어 스냅샷은 `waiting_approval_node_ids`에서 같은 이벤트를 되살린다(`LiveAndSnapshotEventParityTest`). 대기 실행의 종료 이벤트 시각은 `finishedAt`이 아니라 `updatedAt`(멈춘 시각)
- 게이트가 대기에 든 뒤 다른 분기가 실패하거나(실행은 `FAILED`) `pauseForApproval`이 0행이면, 라이브는 이미 `APPROVAL_REQUESTED`를 흘렸지만 스냅샷 재생엔 없다(재생은 `WAITING_APPROVAL`일 때만 게이트 이벤트를 되살린다). **소비자는 `EXECUTION_COMPLETED`가 `WAITING_APPROVAL`을 실었을 때만 게이트를 대기로 다룰 것**

## 영속 (domain/, repository/)

| 엔티티 | 테이블 | 비고 |
|--------|--------|------|
| `Workflow` | `workflows` | |
| `WorkflowVersion` | `workflow_versions` | 실행 시점 버전 고정 참조 |
| `WorkflowExecution` | `workflow_runs` | status, triggerType, startedAt/finishedAt, traceId, `retry_exhausted`, `trigger_data`, `retried_by_execution_id`, `error_message` |
| `WorkflowExecutionLog` | `node_runs` | nodeId, nodeType, status, input/output JSON(TEXT), errorMessage, durationMs, traceId, prompt/completion/total tokens(재시도 전 회차 합 — 실패 회차 포함), `attempt_count` |
| `ChatMessage`/`ChatSession` | chat/ 하위 | 워크플로우 채팅 |
| `NodeTestSample` | `node_test_samples` | 노드 단일 테스트의 최신 결과 1건(UNIQUE workflow_id+node_id). 출력은 `SensitiveDataMasker` 적용 후 TEXT JSON. 삭제는 `WorkflowCrudService.deleteWorkflowInternal`이 워크플로우 행보다 먼저 |
| `AppTriggerSubscription` | `app_trigger_subscriptions` | 활성 앱 트리거 1건(UNIQUE workflow_id+node_id, FK 없음). 행 있음 = 켜짐. `AppTriggerSubscriptionService.sync`가 `WorkflowCrudService` 생성·수정·AI 저장·활성화·비활성화에서 **트랜잭션 안**으로 맞춘다(Quartz만 afterCommit). config는 스냅샷만(Gmail `{query}`, GitHub `{repoId}` = `resource_key` — 스냅샷·resource_key·접근 검증·폴링 시작 상태는 `TriggerHandlerRegistry`의 핸들러(api `GmailTriggerClient`·`GitHubIssueSource`)가 정하고 파서도 거기 있다: `GmailTriggerClient.query`, `GitHubIssueSource.repoId`(숫자·숫자 문자열, 형식 오류 400)). Gmail 커서 `poll_cursor` — 리셋 시 `{afterSec: now}`만 쓰고 나머지 키(`floorSec`·`okSec`·`seen`)는 api `GmailCursor`가 채운다, triggerType·스냅샷(config 전체) 변경 시만 핸들러 `pollStart`로 리셋. 앱 트리거 노드 = 첫 번째 TRIGGER 노드이고 그 event의 `trigger_type` 핸들러가 `subscribes()`일 때다(`findAppTrigger`, `EventCatalog`·`TriggerHandlerRegistry` 주입). PG triggerType도 같은 노드에서 계산하므로 "노드 선언 없음" 400은 없다. 스윕 클레임은 `lockDue`(네이티브 `FOR UPDATE SKIP LOCKED`). 삭제는 `deleteWorkflowInternal` |
| `WorkflowWebhookToken` | `workflow_webhook_tokens` | 웹훅 수신 주소 토큰, 워크플로우당 1개(UNIQUE workflow_id, FK 없음). 평문 없이 SHA-256 hex만 저장, 재발급은 같은 행의 해시 교체. 수신 조회 `findWorkflowIdByToken`은 JDBC(OSIV 커넥션 점유 방지). 삭제는 `deleteWorkflowInternal` |
| `WorkflowDefinitionDocument` | Mongo | nodes/edges 정의. PG `mongoDefinitionId`로 조인 |

enum 실제 값: `ExecutionStatus`=PENDING/RUNNING/SUCCESS/FAILED/WAITING_APPROVAL, `ExecutionLogStatus`=SUCCESS/FAILED/SKIPPED, `TriggerType`=MANUAL/WEBHOOK/SCHEDULE/GMAIL_NEW_EMAIL/GITHUB_NEW_ISSUE(종류별 동작은 `trigger/TriggerHandler` — 구독형은 `subscribes()`).

새 컬럼 (IEUM-BE-46):
- `node_runs.attempt_count` — 결과가 나오기까지의 시도 횟수. 재시도 없이 끝나면 1, **`SKIPPED`(재처리 스킵)은 0**
- `workflow_runs.retry_exhausted` — 재시도 대상 실패로 `maxAttempts`를 다 쓰고도 실패했는지. 판정은 런타임이 하고 컬럼엔 결과만 남는다
- `workflow_runs.trigger_data` — 트리거가 전달한 초기 입력. **AES-256 암호문(TEXT)이다 — 직접 파싱하지 말 것.** 복호는 `WorkflowExecutionService.decryptTriggerData()` 하나뿐이다(잡 큐 워커·재처리가 공유). 조회 API 응답에 그대로 노출 금지
- `workflow_runs.retried_by_execution_id` — 이 실행을 재처리하려 만든 새 실행. 역방향(재처리 → 원본) 조회는 `findByRetriedByExecutionId`

새 컬럼 (IEUM-BE-50):
- `workflow_runs.error_message` — **실행 단위** 실패 사유. 노드 로그(`node_runs.error_message`)가 남지 않은 실패(런타임 진입 전 실패, 고립 실행 sweeper 확정)에서 유일한 원인 기록이라 `markAsFailed`와 런타임 1단계 실패(`recordErrorIfNotTerminal` — 정의 로드·TRIGGER 없음·정적 검증)가 채운다. 노드가 특정된 실패는 여기가 null이고 노드 로그에 원인이 있다. 대시보드 에러 목록이 "노드 로그 → 이 컬럼 → 기본 문구" 순으로 고른다

새 컬럼 (IEUM-BE-45):
- `workflow_runs.waiting_approval_node_ids` — 멈춘 순간 대기 중인 게이트 ID(JSON 배열 TEXT). `pauseForApproval`만 쓴다. 승인·거부 뒤에도 지우지 않는다(이력) — "대기 중"은 `status = WAITING_APPROVAL`일 때만의 의미. 읽기는 `WorkflowExecution.waitingApprovalNodeIdList()`(해석 실패 시 빈 목록 → 게이트 재대기, 우회 아님)
- `workflow_runs.approval_deadline` — 멈춘 시각 + `ieum.workflow.approval.timeout`

**DDL은 Flyway가 아니라 `ddl-auto: update`** — 마이그레이션 파일 없음. 컬럼 추가는 엔티티 필드만 넣으면 된다.
단, **`nullable = false` 컬럼을 기존 행이 있는 테이블에 추가할 때는 `@ColumnDefault`가 반드시 필요하다.** 없으면 PostgreSQL이 DDL을 거부하는데 `ddl-auto: update`는 그 오류를 경고로만 남기고 부팅을 계속해 **컬럼 없이 앱이 뜬다.** 테스트는 `ddl-auto: create-drop`(빈 스키마)이라 이 사고를 잡지 못한다 — 이번에 `retry_exhausted`·`alert_target`이 걸릴 뻔했다.
**enum 값을 추가하면 DB CHECK 제약도 손으로 고쳐야 한다.** Hibernate 6는 `@Enumerated(STRING)` 컬럼을 `CHECK (col IN (...))`와 함께 만드는데 `ddl-auto: update`는 이 제약을 갱신하지 않는다 — 새 값을 쓰는 순간 제약 위반이다. 테스트(`create-drop`)는 새 스키마라 못 잡는다. `docs/schema/V7`(connected_accounts.provider)·`V8`(workflow_runs.status·node_runs.node_type, IEUM-BE-45)·`V9`(node_runs.node_type에 `ACTION`)·`V10`(workflows·workflow_runs.trigger_type에 앱 트리거 2값)이 그 수동 DDL이다 — 배포 전에 대상 DB에 적용할 것. **`app_trigger_subscriptions.trigger_type`도 Hibernate가 CHECK를 만든다**(V10 시점 값으로 생성) — `TriggerType`에 값을 더하면 이 테이블 제약도 수동 DDL 대상이다.

## 트리거 추가 절차 (IEUM-BE-93)
새 트리거 종류는 아래만 손댄다. 실행기(`TriggerNodeExecutor`)·구독(`AppTriggerSubscriptionService`)·활성화(`WorkflowCrudService.activateWorkflow`)·노드 테스트(api `NodeTestService`)에는 종류별 분기가 없고 `TriggerHandlerRegistry.get(type)`에 위임한다. SCHEDULE(Quartz)·WEBHOOK(토큰 주소)·Gmail 폴링·GitHub 수신은 각 종류의 고유 수신 경로라 새 트리거가 고치지 않는다.

1. **카탈로그 이벤트** — ieum-node-catalog에 `events/<앱>/<이벤트>.json`(`node_type: TRIGGER`, `trigger_type: <새 값>`)을 머지한다. BE는 **서브모듈 갱신과 enum 상수 추가를 같은 커밋**으로 한다. 카탈로그만 먼저 올리면 `EventCatalog` 로더가 모르는 trigger_type으로 기동을 막고, enum만 먼저 올리면 `EventCatalogTest.everyTriggerType_hasExactlyOneEvent`가 깨진다. agent 서브모듈도 같은 카탈로그 커밋이어야 한다. 다르면 기동 뒤 `AgentCatalogDiffReporter`가 `[CatalogDiff]` warn을 남긴다.
   - agent는 **서브모듈 갱신과 `ieum-agent/api/schemas/generate_workflow.py`의 `TRIGGER_TYPES` 추가를 같은 커밋**으로 한다 — 빠지면 agent 로더(`core/template_registry.py` `validate_registry`)가 모르는 trigger_type으로 기동을 막는다.
   - 카탈로그에 **새 auth 값이나 새 ACTION tool_key 접두사**를 올릴 땐 BE `ToolAuthRule`·실행기 변경을 먼저 머지·배포한다 — 순서가 뒤집히면 api `CatalogStartupCheck`가 dev 부팅을 막는다.
2. **`TriggerType` 상수** — 이름은 **20자 이하**다. `workflows.trigger_type`이 `VARCHAR(20)`(`Workflow.java` `@Column(length = 20)`)이다. `app_trigger_subscriptions`는 30자, `workflow_runs`는 255자다.
3. **`TriggerHandler` 구현 `@Component` 하나**(포트: `com.ieum.workflowcore.trigger`) — `type()`, `output()`(기본 = 런타임이 심은 페이로드 복사. 부작용 금지, 재처리 때 다시 불린다). 앱 이벤트형이면 `subscribes()=true`, `snapshot`, `resourceKey`(webhook 라우팅용), `pollStart`, `verifyAccess`(**외부 리소스를 고르는 트리거는 반드시 오버라이드** — 기본값은 검증 없음 = IDOR), `nodeTestMode`(LISTEN·FETCH — 구독형이 RUN이면 `CatalogStartupCheck`가 기동을 막는다), `fetchSample`(FETCH일 때)을 구현한다. **`verifyAccess`가 보는 외부 리소스는 `resourceKey`로도 내라** — 구독 동기화의 재검증 생략 키가 (type, resourceKey)라서, null로 두면 리소스를 바꿔 저장해도 재검증을 건너뛴다(잠재 IDOR). **폴링형 스냅샷의 값은 문자열로 둔다** — 구독 config는 JSON 칼럼이라 다시 읽으면 숫자가 Integer로 돌아와(`Long` 123 ≠ `Integer` 123) `reconfigure`가 저장마다 "바뀜"으로 보고 커서를 리셋한다(GitHub repoId는 커서가 없어 무해). 외부 API를 부르는 앱 핸들러는 api 모듈 클래스가 구현한다(Gmail = `GmailTriggerClient`, GitHub = `GitHubIssueSource`). 핸들러가 빠지면 api `CatalogStartupCheck`가 기동을 막고, 같은 type이 둘이면 `TriggerHandlerRegistry`가 막는다.
4. **수신 경로** — 폴링 스위퍼나 webhook 컨트롤러를 새로 쓰고, 발화는 api `AppTriggerFirer.fire(workflowId, type, output)`로 한다. 웹훅형이면 auth `SecurityConfig`(`auth/src/main/java/com/ieum/auth/config/SecurityConfig.java`의 permitAll 목록)에 **그 경로 하나만** 추가하고, 서명·토큰 검증을 수신 서비스에 둔다(선례: `/api/v1/integrations/github/webhook` — HMAC).
5. **PG CHECK 제약 수동 DDL** — `docs/schema/V<다음 번호>__add_<값 소문자>_trigger_type.sql`을 만들고 배포 전에 대상 DB(dev·운영)에 직접 적용한다. 테스트(`create-drop`)는 새 스키마라 이 문제를 잡지 못한다. 형식은 `V10`과 같다(IF EXISTS 없이, DROP·ADD를 한 문장으로). 예: 새 값 `NEW_TRIGGER`
   ```sql
   ALTER TABLE workflows
       DROP CONSTRAINT workflows_trigger_type_check,
       ADD CONSTRAINT workflows_trigger_type_check
           CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE', 'GMAIL_NEW_EMAIL', 'GITHUB_NEW_ISSUE', 'NEW_TRIGGER'));

   ALTER TABLE workflow_runs
       DROP CONSTRAINT workflow_runs_trigger_type_check,
       ADD CONSTRAINT workflow_runs_trigger_type_check
           CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE', 'GMAIL_NEW_EMAIL', 'GITHUB_NEW_ISSUE', 'NEW_TRIGGER'));

   ALTER TABLE app_trigger_subscriptions
       DROP CONSTRAINT app_trigger_subscriptions_trigger_type_check,
       ADD CONSTRAINT app_trigger_subscriptions_trigger_type_check
           CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE', 'GMAIL_NEW_EMAIL', 'GITHUB_NEW_ISSUE', 'NEW_TRIGGER'));
   ```
   적용 전에 `\d workflows`·`\d workflow_runs`·`\d app_trigger_subscriptions`로 실제 제약 이름을 확인한다. 특히 `app_trigger_subscriptions`는 docs/schema에 DDL 선례가 없다. Hibernate가 만든 이름 없는 CHECK에 PG가 붙이는 기본 이름(`<테이블>_<컬럼>_check`)으로 추정한 이름이므로, 다르면 DROP 이름만 바꾼다.

## 스케줄러 (scheduler/, config/)
Quartz. `WorkflowScheduler`(등록/해제), `WorkflowScheduleJob`(실행), `ScheduleJobRestorer`(부팅 시 복원), `WorkflowCleanupScheduler`, `JobKeyGenerator`.
`WorkflowScheduleJob`은 `ExecutionJobEnqueuer`로 api의 잡 큐에 넣고 바로 반환한다(IEUM-BE-51) — 수동·웹훅·재처리와 같은 재시작 복구를 받는다. 투입이 실패하면(Redis 장애·Stub) 유실되지 않게 `SyncExecutionRuntime`을 직접 부르고, 이 경우만 내구성이 없다.
**겹침 방지는 `@DisallowConcurrentExecution`만으로 성립하지 않는다** — 잡이 실행 끝까지 블록하지 않으니 다음 발화가 그대로 들어온다. 그래서 같은 워크플로우에 끝나지 않은(PENDING/RUNNING) SCHEDULE 실행이 있으면 `prepareExecution` 전에 스킵한다(`WorkflowExecutionService.hasUnfinishedScheduleRun`). 판정은 `startedAt > now - workflow.execution.stuck.threshold`로 하한을 둔다 — sweeper는 `RUNNING`만 정리하므로 준비만 되고 버려진 고아 `PENDING`이 하한 없이 잡히면 그 스케줄이 **영구히 멈춘다.** `@DisallowConcurrentExecution`은 여전히 필요하다(JobKey가 워크플로우당 1개라 판정↔레코드 생성 사이에 다른 발화가 끼지 않게 직렬화). 재처리 API가 만든 실행도 원본의 `triggerType`(SCHEDULE)을 물려받으므로 겹침 판정에 걸린다.

`WorkflowCleanupScheduler`는 세 가지를 돈다(셋 다 `@Scheduled` cron, Quartz 아님):
- `cleanupOrphanWorkflows()` — 고아 빈 워크플로우 hard delete
- `failStuckRunningExecutions()` — **고립 `RUNNING` 실행 sweeper**(IEUM-BE-50). 프로세스가 죽어 런타임이 종료 처리를 못 한 실행은 영원히 `RUNNING`으로 남고, 재처리 API가 `FAILED`만 받으므로 다시 돌릴 수 없다. 재처리로 생긴 실행이 이렇게 굳으면 원본까지 "재처리 진행 중"으로 판정돼 **영구히 막힌다.** 확정은 `WorkflowExecutionService.markAsFailed()`에 맡긴다 — 런타임 밖 실패 확정 경로가 이미 종료 상태 가드·알림·커밋 후 발신을 갖췄다. 런타임의 `finalizeFailure`는 실행 중 인스턴스 상태를 쥔 private 경로라 타지 않는다. `retryExhausted`는 false(재시도 소진이 아니라 재시도 판정 자체가 못 이뤄진 실패)
- `expireWaitingApprovals()` — 승인 기한(`approval_deadline`)이 지난 `WAITING_APPROVAL`을 `markAsFailed(id, "승인 만료", WAITING_APPROVAL)`로 확정(IEUM-BE-45) — 기대 상태를 넘겨 그 사이 승인·거부로 끝난 실행은 건드리지 않는다. 행 잠금이라 같은 순간의 승인·거부와 하나만 이긴다. SSE는 건드리지 않는다(멈출 때 이미 닫혔다). 정밀도는 주기(10분)

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
| `workflow.execution.stuck.threshold` | `PT2H` | 이 시간 넘게 `RUNNING`인 실행을 고립으로 보고 `FAILED`로 확정. **내리지 말 것** — 잡 큐 회수(`reclaim-min-idle` 10분)로 복구될 실행을 먼저 FAILED로 굳히면 워커가 종료 상태로 보고 건너뛰어 복구가 취소된다. 스케줄 겹침 판정의 하한으로도 쓴다 |
| `ieum.workflow.approval.timeout` | `PT24H` | 승인 게이트 기한(멈춘 시각 + 이 값). `SyncExecutionRuntime`의 `@Value` 기본값이고 yml에 선언돼 있지 않다 |

`retry.*`는 `RetryProperties`(`@ConfigurationProperties`)의 필드 기본값이고 yml에 선언돼 있지 않다 — 장애 시 `ai-max-attempts: 1`로 재시도를 전역으로 끌 수 있게 코드 상수가 아니라 설정으로 뒀다. 노드 config의 `retry` 선언이 이 기본값보다 우선한다.

## 주의사항
- `node_runs`의 input/output에 자격증명 원문 저장 금지 — `SensitiveDataMasker.mask()`(util/)가 `apiKey/api_key/token/secret/password/Authorization` 키를 `***`로 마스킹한다. 키 이름과 별개로 `auth` 키 바로 아래 Map 중 `type`이 `secret`·`plain`인 것의 `value`도 가린다(`tools[].auth` 비밀 원문 — IEUM-BE-71). `auth` 밖의 같은 모양은 건드리지 않는다 — 노드 출력이 가려지면 재처리(`loadReusableNodeOutputs`)가 `***`를 하류에 넘긴다. 새 민감 키는 `SENSITIVE_KEYS`에 추가. **중첩 Map·List 내부까지 재귀 적용된다** (IEUM-BE-62에서 확장 — 그 전에는 최상위 키만 검사했다). `workflow_runs.trigger_data`는 마스킹이 아니라 AES-256 암호화다 — `mask()`의 호출부는 `SyncExecutionRuntime` 하나뿐이다. 같은 클래스의 `containsWebhookUrl()`은 api `RawWebhookUrlGuard`(REST 저장 `WorkflowService` + agent 저장 `ChatService` 공용)가 노드 `config.url` 원문 웹훅 저장을 거부할 때, `isSlackWebhookUrl()`/`isDiscordWebhookUrl()`은 api `WebhookCredentialService.create()`가 등록 URL이 provider의 웹훅 호스트인지 볼 때 쓴다(전체 일치 + https 전용). **웹훅 도메인 조각은 `SLACK_WEBHOOK_PREFIX`·`DISCORD_WEBHOOK_PREFIX` 두 곳뿐이어야 한다** — 마스킹·저장 거부·등록 검증이 전부 이 조각을 조립해 쓰므로 도메인이 늘면 여기만 고친다
- `node_runs.error_message`·SSE `nodeFailed`·실패 알림의 오류 메시지는 `SensitiveDataMasker.maskWebhookUrl(String)`로 웹훅 URL 비밀 구간을 가린다(외부 API 오류 본문에 URL이 실려 올 수 있다)
- 실행 로그 저장 실패는 실행 전체를 중단시키지 않음(warn만) — 이력 누락 가능성이 설계상 허용됨. **그래서 INSERT가 실패하지 않게 막는 게 중요하다** — `node_runs.error_message`는 VARCHAR(255)라 `saveExecutionLog`가 255자로 자른다(ACTION은 외부 API 오류 본문을 그대로 싣는다. 자르지 않으면 FAILED 행이 조용히 사라진다)
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
