# API Module Context

## 역할
메인 진입점 (SpringBoot Application). REST Controller, WebSocket/SSE, DTO, Swagger 설정, 그리고 **workflow-core Provider 포트의 실구현**을 담당한다. 유일하게 `org.springframework.boot` 플러그인이 적용된 실행 가능한 모듈이다.

## 핵심 규칙
- Controller는 반드시 Swagger 문서화: `@Tag` (클래스), `@Operation` (메서드)
- Docs 인터페이스 분리 패턴: `{Domain}ControllerDocs` 인터페이스에 Swagger 어노테이션 선언, Controller가 이를 구현
- 모든 응답은 `ApiResponse<T>` 래핑
- Request DTO에 `@Valid` + Bean Validation 어노테이션 (`@NotBlank`, `@Email` 등)
- Response DTO에 `from(Entity)` 정적 팩토리 메서드
- 비즈니스 로직은 Controller에 두지 않음 — Service에 위임

## 도메인별 구현 현황
클래스 전수 목록은 코드가 진실. 여기선 도메인 단위로만 잡는다.

| 패키지 | Controller | 내용 |
|--------|-----------|------|
| `auth` | AuthController | 회원가입·로그인·토큰 갱신 |
| `user` | UserController | 내 정보 조회/수정 |
| `oauth` | Google/Notion/GitHubOAuthController, OAuthConnectionController | 소셜 연동·토큰 저장·연동 해제 |
| `credential` | CredentialController | AI API Key BYOK CRUD·검증 |
| `provider` | ProviderController | 지원 프로바이더 목록 |
| `workflow` | WorkflowController, WorkflowDashboardController | 워크플로우 CRUD·실행·실행 이력 조회·SSE 진행 스트림, 실패 실행 재처리(`POST /api/v1/workflows/executions/{executionId}/retry`, 202), 대시보드 요약/최근실행/에러, 승인 대기 실행 승인·거부(`POST /api/v1/workflows/executions/{executionId}/approve` 202 새 실행 / `.../reject` 200) |
| `chat` | ChatController | 워크플로우 채팅 (+ `AgentClient`가 ieum-agent 호출, WebSocket 핸들러) |
| `webhook` / `webhookcredential` | WebhookController, WebhookCredentialController | 웹훅 트리거 수신(노드 테스트가 listen 중이면 첫 1건을 샘플로 가로챔 — Redis `webhook:listen:{workflowId}`, `WebhookListenStore`가 GETDEL로 소비. 비활성·triggerType 불일치면 샘플만 저장하고 data 없는 202)·웹훅 크레덴셜, 실패 알림 대상 지정(`PUT /api/v1/webhook-credentials/{id}/alert-target`) |
| `alert` | (Controller 없음) | 실패 알림 발신 — `AlertCooldownStore`, `DiscordWebhookSender` |
| `integration` | IntegrationWorkflowController, IntegrationOptionController | 연동 서비스별 워크플로우 조회, 노드 설정 드롭다운 선택지(`GET /api/v1/integrations/{app}/options/{resource}` — 공급원 빈 `OptionSource`를 key `{app}.{resource}`로 고름. `@Transactional` 금지: 토큰 갱신 저장이 readOnly에 합류해 유실). IEUM 내부 리소스 공급원(IEUM-BE-76): `ai.models`·`ieum.credentials`(입력 llmProvider)·`ieum.webhooks`/`slack.webhooks`/`discord.webhooks`(`WebhookOptionSourceConfig`가 한 구현으로 3빈)·`ieum.mcp_servers`. GitHub 공급원(노드 카탈로그 ② BE-b): `github.owners`(본인 login + 소속 org — org 조회가 4xx면 본인만)·`github.repos`(입력 `owner`, cursor=페이지 번호, 본인이면 `/user/repos?affiliation=owner`·아니면 `/orgs/{owner}/repos`). **목록은 GitHub App 설치 범위로 한정**, `GitHubApiReader`가 토큰·헤더·오류 매핑(`GITHUB_API_UNAVAILABLE` 503) 공유 |
| `mcp` | McpServerCatalogController | MCP 서버 카탈로그 |
| `prompt` | PromptTemplateController | 프롬프트 템플릿 CRUD·테스트 실행 |
| `beta` | BetaUsageController | 베타 플랫폼 키 사용량(%) 조회 |
| `node` | NodeCatalogController | 노드 카탈로그(`GET /api/v1/nodes/catalog`, IEUM-BE-75) — agent `GET /v1/nodes/catalog`를 `AgentClient`로 그대로 전달, 캐시 없음. 옛 도구 스키마 API(BE-71)를 대체 |

## 실행 트리거
`workflow/WorkflowExecutionRunner` — 실행 진입점. Redis Stream 잡 큐(`ieum:exec:jobs`)에 executionId만 발행하고, 같은 프로세스의 워커가 꺼내 `SyncExecutionRuntime`을 돌린다. 큐 발행이 실패하면(Redis 장애) 기존 `@Async` 직접 실행으로 폴백한다. 실행 레코드 생성 자체는 workflow-core `WorkflowExecutionService.prepareExecution()`.

`run()`은 큐 경유(+폴백), `executeNow()`는 호출 스레드에서 즉시 실행 — 워커와 폴백이 공유하는 유일한 실행 지점이라 실패 기록(`markAsFailed`)이 한 곳에만 있다. `executeNow()`가 `WorkflowExecutionService.loadReusableNodeOutputs()`로 재처리 스킵 대상을 조회해 런타임에 넘긴다(일반 실행은 빈 Map).

`workflow/queue/` — `ExecutionJobQueue`(발행), `ExecutionJobWorker`(소비), `ExecutionJobQueueBootstrap`(그룹 생성·소비 시작·고아 잡 주기 회수·폴링 오류 복구), `ExecutionJobQueueConfig`(컨테이너 빈).
**워커를 별도 프로세스로 빼지 말 것** — `ExecutionEventPublisher`가 in-memory `Sinks.Many`라 SSE가 즉시 깨진다. 큐의 목적은 수평 확장이 아니라 재시작 복구다.
**배달 보장은 at-least-once다 — exactly-once가 아니다.** 실행 도중 프로세스가 죽으면 run은 `RUNNING`으로 남고 잡은 pending에 남아 회수되어 **처음부터 다시** 실행된다(이미 부작용을 낸 노드까지 되풀이). 중복 가드는 ① 종료·대기 상태(SUCCESS/FAILED/WAITING_APPROVAL) ② 이 프로세스의 in-flight Set 두 가지뿐이다. 이 큐 위에 뭘 얹을 때 exactly-once로 오해하지 말 것.
**단일 인스턴스 배포(stop-then-start) 전제** — 컨슈머 이름이 상수라 인스턴스를 구분하지 않는다. 롤링 배포로 두 인스턴스가 겹치면 고아 잡 회수가 살아 있는 쪽의 in-flight를 뺏어 이중 실행할 수 있다(`ieum.workflow.queue.reclaim-min-idle`, 기본 10분이 유일한 안전장치). 스케일아웃은 SSE 허브 교체가 선행 조건.
고아 잡 회수는 **부팅 1회가 아니라 주기 실행**(`@Scheduled`)이다 — `reclaim-min-idle` 때문에 재시작 직후엔 고아 잡의 idle이 아직 짧아 회수 대상이 아니고, 다시 볼 기회가 없으면 영영 유실된다.
Quartz 스케줄 실행(`WorkflowScheduleJob`)은 workflow-core 포트 `ExecutionJobEnqueuer` → `config/DefaultExecutionJobEnqueuer` → `ExecutionJobQueue.enqueue()`로 같은 큐를 탄다(IEUM-BE-51). 투입 실패 시 폴백은 러너가 아니라 잡이 `SyncExecutionRuntime`을 직접 부르는 것이다.

`workflow/service/ExecutionRetryService` — 실패 실행 재처리. DLQ는 별도 저장소가 아니라 `status=FAILED`인 실행 목록 자체다. 원 실행을 되살리지 않고 **같은 버전·같은 트리거 입력으로 새 실행을 만들어** 큐에 넣고, 원 실행엔 `retriedByExecutionId` 링크만 남긴다. 원본 행을 비관적 락(`lockExecutionWithVersion`)으로 읽어 동시 요청이 재처리를 둘 만드는 것을 막고, 큐 투입은 `afterCommit`에서 한다(워커가 링크를 읽어야 스킵 대상을 안다).

`workflow/service/ExecutionApprovalService` — 승인 게이트(IEUM-BE-45)에서 멈춘 `WAITING_APPROVAL` 실행의 승인·거부. 원 실행 행 잠금(`lockExecutionWithVersion`) → 소유자 검증(ADMIN 우회 없음) → 대기 상태가 아니면 409 `EXECUTION_NOT_WAITING_APPROVAL`. 승인은 원 실행을 되살리지 않고 **재처리와 같은 `ExecutionRetryService.startContinuation()`**(같은 버전·트리거 입력으로 새 실행 + `retriedBy` 링크 + `afterCommit` 큐 투입)으로 이어진 실행을 만들고 원 실행은 SUCCESS. 응답은 새 실행이라 프론트는 그 ID로 SSE를 다시 구독한다. 거부는 FAILED + 사유이고, 사용자 결정이라 `markAsFailed`(알림)를 타지 않는다. 거부 사유 `@Size(max=200)` — `node_runs.error_message`가 VARCHAR(255)다. `startContinuation`을 별도 빈으로 빼지 말 것 — `ExecutionRetryServiceTest`의 `@InjectMocks`가 깨진다.

## 워크플로우 저장 규칙 (ACTION·brand·cron)
- `ACTION` 노드는 REST 저장을 허용한다(`NodeType` enum만으로 통한다). config는 AI 노드 `tools[0]` 모양이라 `NodeCredentialGuard`·`WorkflowServiceType`이 변경 없이 적용된다. `RawWebhookUrlGuard`는 최상위 `config.url`만 본다 — `tools[].config.webhook_url` 원문은 막지 않는다(AI 노드와 동일한 기존 한계).
- **brand 유지**(`NodeDefinitionMerger`) — REST 수정은 노드 config를 통째로 교체하지만, 요청 config에 `brand` 키가 없으면 이전 `config.brand`를 이어받는다(FE 아이콘·연동 목록의 유일한 근거). 요청이 명시한 값은 `null`·빈 문자열도 존중한다.
- **연동 서비스별 목록**(`IntegrationServiceType`) — `GOOGLE`만 brand 셋(`google`·`gmail`·`sheets`)으로 Mongo `$in` 조회한다. agent가 Google 도구 노드에 세 값을 섞어 넣는다. 나머지 서비스는 brand 하나. (집계 파이프라인의 실 Mongo 동작은 단위 테스트로 못 본다 — 바인딩만 `WorkflowDefinitionRepositoryPipelineTest`가 고정.)
- **cron은 TRIGGER 노드가 진실원**(`TriggerNodeSchedule`) — 노드 `config.triggerType`이 있으면 REST 생성·수정과 AI 저장 모두 `triggerType`과 `cron`을 **둘 다 노드에서** 가져와 최상위 값을 덮는다(노드에 cron이 없으면 null — 최상위·저장된 값으로 채우지 않는다). 수정은 `NodeDefinitionMerger` 병합 결과 기준이다. 노드에 `triggerType`이 없을 때만 기존 최상위 값·`resolveCronExpression` 폴백을 쓴다. 알 수 없는 `triggerType`은 400(`INVALID_WORKFLOW`).

## 노드 단일 테스트 (workflow/service/NodeTestService, controller/NodeTestController)
`POST /api/v1/workflows/{workflowId}/nodes/{nodeId}/test`(body `{node?, input?}`) / `GET .../sample`. 실행·샘플 저장은 workflow-core `NodeTestRunner`, 여기는 소유자 검사(`getWorkflowByOwner`, 항상 먼저)·body 노드 가드·webhook 분기만. `WorkflowController`에 붙이지 않은 이유: 그 생성자를 여러 테스트가 직접 호출한다.
- body `node`는 저장 경로와 같은 세 가드(`NodeCredentialGuard.rejectForeignCredentialId`·`rejectInlineSecret`, `RawWebhookUrlGuard`)를 거친다 — 저장 없이 곧바로 실행되기 때문. `node.id ≠ nodeId`면 400. body가 없으면 저장된 최신 버전의 노드
- 트리거가 webhook(`config.triggerType`, 비면 워크플로우 최상위 triggerType)이면 실행하지 않고 `WebhookListenStore.start` → `{status: LISTENING, webhookUrl, expiresAt}`. `webhookUrl`은 **경로**(`/api/v1/webhooks/{workflowId}`)다 — BE에 공개 base URL 설정이 없어 FE가 API origin을 붙인다
- 실패한 테스트는 HTTP 200 + `status: FAILED`. 샘플 누락은 400 `TEST_SAMPLE_MISSING` + `data.missingNodeIds`(`TestSampleMissingException` 전용 핸들러). `@Transactional` 금지(외부 호출을 한 트랜잭션에 묶지 않기 위해 — 저장소 호출은 각자 트랜잭션. OSIV 기본 on이라 커넥션은 요청 끝까지 유지됨)

## config/ — Provider 포트 실구현
workflow-core가 선언한 포트 11개를 여기서 `Default*`로 구현해 Stub을 대체한다 (`@Primary`).
`DefaultCredentialProvider`, `DefaultGoogleTokenProvider`, `DefaultNotionTokenProvider`, `DefaultGitHubTokenProvider`, `DefaultWebhookCredentialProvider`, `DefaultMcpCatalogProvider`, `DefaultUserRoleProvider`, `DefaultBetaPlatformProvider`, `DefaultIdempotencyStore`, `DefaultAlertNotifier`, `DefaultExecutionJobEnqueuer`.

`DefaultIdempotencyStore`(Redis SETNX)·`AlertCooldownStore`는 **Redis 장애를 삼키고 진행을 허용한다** — 중복 호출·알림 도배 위험을 감수하고 가용성을 택한 의도적 트레이드오프다. 예외를 전파하도록 되돌리지 말 것.

기타 설정: `JpaConfig`, `SwaggerConfig`, `RestTemplateConfig`, `WebSocketConfig`, `WebSocketAuthInterceptor`.

## 실패 알림 (alert/, config/DefaultAlertNotifier)
3점 세트로 나눠 둔다 — `DefaultAlertNotifier`(대상 결정 + 문구 구성), `AlertCooldownStore`(Redis SETNX, **워크플로우당 5분 쿨다운** — 반복 실패가 채널을 도배하는 것을 막는다), `DiscordWebhookSender`(HTTP 발신만).
- 발신 대상 둘: 운영자 채널(`DISCORD_OPS_WEBHOOK_URL`, 미설정이면 조용히 no-op) + 워크플로우 소유자 채널(`WebhookCredentialProvider.resolveAlertWebhookUrl()`)
- 소유자 채널은 `webhook_credentials.alert_target = true`인 활성 **DISCORD** 크레덴셜 하나. 지정은 `WebhookCredentialService.setAlertTarget()`이 provider별로 하나만 유지되게 기존 것을 내리고 세운다. 지정이 없으면 소유자 발신 생략
- 알림 문구에 담기는 값은 `AlertNotifier.ExecutionFailureAlert` record의 필드뿐이다 — 자격증명·API 키·프롬프트 원문을 넣지 말 것(채널로 그대로 새어 나간다)
- `DiscordWebhookSender`는 공유 `RestTemplate`(connect 10s / read 30s)을 쓴다. 발신이 실행 런타임 메인 스레드에서 동기 호출되므로 **타임아웃 없는 클라이언트로 바꾸지 말 것** — 풀 슬롯이 마르고 잡 소비가 멈춘다
- webhook URL을 로그에 남기지 말 것 — URL 자체가 비밀이다(`e.getMessage()`도 URL을 담을 수 있어 상태 코드만 남긴다)

## 설정 키 (이 모듈이 읽는 것 중 실행 신뢰성 관련)
| 키 | 기본값 | 용도 |
|----|--------|------|
| `ieum.workflow.queue.reclaim-min-idle` | `PT10M` | 이 시간 넘게 방치된 pending만 회수. **0으로 두지 말 것** — 살아 있는 소비자의 in-flight를 훔치지 않는 유일한 안전장치이자, 뒤집으면 재시작 복구 지연 상한이다. 워크플로우 최대 소요시간보다 넉넉히 길어야 한다. MARKER 마커 TTL(기본 5분)과 서로 영향을 줘 크래시 재배달의 마커 보호는 타이밍 의존이다(IEUM-BE-53) |
| `ieum.workflow.queue.reclaim-interval` | `PT1M` | 고아 잡 회수 주기 |
| `ieum.workflow.queue.poll-error-backoff` | `PT5S` | 폴링 실패 시 대기(스핀·로그 폭주 방지) |
| `ieum.beta.platform-key.allowed-models` | `gemini-3.7-flash` | platform 모드에서 재시도 모델 fallback이 시도할 수 있는 모델. platform 키는 Gemini 한 장이라 비-Gemini를 넣지 말 것 |
| `DISCORD_OPS_WEBHOOK_URL` | (빈 문자열) | 운영자 실패 알림 채널. `@Value`로 직접 읽는 환경변수 — 비어 있으면 발신 안 함 |

큐 관련 세 키는 yml에 선언돼 있지 않고 `ExecutionJobQueueBootstrap` 생성자의 `@Value` 기본값이다. `allowed-models`는 `application.yml`(`BETA_ALLOWED_MODELS`) + `BetaPlatformKeyProperties`.

## 공통
- `IeumApplication` — `@SpringBootApplication(scanBasePackages = "com.ieum")`. 누락 시 다른 모듈 Bean 스캔 안 됨
- `common/GlobalExceptionHandler` — `@RestControllerAdvice`, CustomException/MethodArgumentNotValid/일반 Exception 처리

## 크로스레포 계약 (ieum-agent 위임)
- 크레덴셜 헤더: `X-LLM-Provider`, `X-LLM-Api-Key`
- 멱등성 헤더: 부수효과가 있는 HTTP·AI·ACTION 노드는 `retry.idempotency` 선언이 없어도 HEADER가 기본이라, 재시도가 켜져 있으면 agent에 `X-Idempotency-Key`를 보낸다(IEUM-BE-54. HTTP 노드는 외부 API에 표준 `Idempotency-Key`). agent가 이 헤더를 소비하며(IEUM-AI-52) 같은 키의 재요청은 도구를 다시 돌리지 않는다 — 소비하지 않는 배포에선 무시될 뿐이라 배포 순서 무관
- 베타 플랫폼 키 모드: `X-Key-Mode: platform` (소문자, ADMIN·TESTER에겐 미전송)
- agent `/v1/execute`·`/v1/chat` 응답 모두 usage 있음(IEUM-AI-48) → chat도 일일 호출 캡 + 토큰 예산 둘 다 적용. 차감 기준은 `usage.totalTokens`(입출력 합산 아님)
- ACTION 노드: agent `POST /v1/actions/execute`(workflow-core `ActionNodeExecutor`가 유일한 호출자 — api `AgentClient`는 쓰지 않는다). 요청 `{nodeId, toolKey, config}`, 응답 `{success, output(dict), errorMessage, errorCode}`. `X-LLM-*`·`X-Key-Mode`는 **보내지 않는다**(LLM을 쓰지 않는다). `X-User-Id`·`X-User-Role`·`X-Node-Id`·`X-Trace-Id`·`X-Idempotency-Key`·`X-Google-Access-Token`·`X-Notion-Token`·`X-GitHub-Token`은 보낸다. 배포 순서는 **agent 먼저**(없으면 404 → `AGENT_BAD_REQUEST` 실패)
