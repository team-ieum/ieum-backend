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
| `workflow` | WorkflowController, WorkflowDashboardController | 워크플로우 CRUD·실행·실행 이력 조회·SSE 진행 스트림, 실패 실행 재처리(`POST /api/v1/workflows/executions/{executionId}/retry`, 202), 대시보드 요약/최근실행/에러 |
| `chat` | ChatController | 워크플로우 채팅 (+ `AgentClient`가 ieum-agent 호출, WebSocket 핸들러) |
| `webhook` / `webhookcredential` | WebhookController, WebhookCredentialController | 웹훅 트리거 수신·웹훅 크레덴셜, 실패 알림 대상 지정(`PUT /api/v1/webhook-credentials/{id}/alert-target`) |
| `alert` | (Controller 없음) | 실패 알림 발신 — `AlertCooldownStore`, `DiscordWebhookSender` |
| `integration` | IntegrationWorkflowController | 연동 서비스별 워크플로우 조회 |
| `mcp` | McpServerCatalogController | MCP 서버 카탈로그 |
| `prompt` | PromptTemplateController | 프롬프트 템플릿 CRUD·테스트 실행 |
| `beta` | BetaUsageController | 베타 플랫폼 키 사용량(%) 조회 |

## 실행 트리거
`workflow/WorkflowExecutionRunner` — 실행 진입점. Redis Stream 잡 큐(`ieum:exec:jobs`)에 executionId만 발행하고, 같은 프로세스의 워커가 꺼내 `SyncExecutionRuntime`을 돌린다. 큐 발행이 실패하면(Redis 장애) 기존 `@Async` 직접 실행으로 폴백한다. 실행 레코드 생성 자체는 workflow-core `WorkflowExecutionService.prepareExecution()`.

`run()`은 큐 경유(+폴백), `executeNow()`는 호출 스레드에서 즉시 실행 — 워커와 폴백이 공유하는 유일한 실행 지점이라 실패 기록(`markAsFailed`)이 한 곳에만 있다. `executeNow()`가 `WorkflowExecutionService.loadReusableNodeOutputs()`로 재처리 스킵 대상을 조회해 런타임에 넘긴다(일반 실행은 빈 Map).

`workflow/queue/` — `ExecutionJobQueue`(발행), `ExecutionJobWorker`(소비), `ExecutionJobQueueBootstrap`(그룹 생성·소비 시작·고아 잡 주기 회수·폴링 오류 복구), `ExecutionJobQueueConfig`(컨테이너 빈).
**워커를 별도 프로세스로 빼지 말 것** — `ExecutionEventPublisher`가 in-memory `Sinks.Many`라 SSE가 즉시 깨진다. 큐의 목적은 수평 확장이 아니라 재시작 복구다.
**배달 보장은 at-least-once다 — exactly-once가 아니다.** 실행 도중 프로세스가 죽으면 run은 `RUNNING`으로 남고 잡은 pending에 남아 회수되어 **처음부터 다시** 실행된다(이미 부작용을 낸 노드까지 되풀이). 중복 가드는 ① 종료 상태(SUCCESS/FAILED) ② 이 프로세스의 in-flight Set 두 가지뿐이다. 이 큐 위에 뭘 얹을 때 exactly-once로 오해하지 말 것.
**단일 인스턴스 배포(stop-then-start) 전제** — 컨슈머 이름이 상수라 인스턴스를 구분하지 않는다. 롤링 배포로 두 인스턴스가 겹치면 고아 잡 회수가 살아 있는 쪽의 in-flight를 뺏어 이중 실행할 수 있다(`ieum.workflow.queue.reclaim-min-idle`, 기본 10분이 유일한 안전장치). 스케일아웃은 SSE 허브 교체가 선행 조건.
고아 잡 회수는 **부팅 1회가 아니라 주기 실행**(`@Scheduled`)이다 — `reclaim-min-idle` 때문에 재시작 직후엔 고아 잡의 idle이 아직 짧아 회수 대상이 아니고, 다시 볼 기회가 없으면 영영 유실된다.
Quartz 스케줄 실행(`WorkflowScheduleJob`)은 큐를 거치지 않아 내구성이 없다 — Provider 포트로 뒤집으면 해결되나 별도 이슈.

`workflow/service/ExecutionRetryService` — 실패 실행 재처리. DLQ는 별도 저장소가 아니라 `status=FAILED`인 실행 목록 자체다. 원 실행을 되살리지 않고 **같은 버전·같은 트리거 입력으로 새 실행을 만들어** 큐에 넣고, 원 실행엔 `retriedByExecutionId` 링크만 남긴다. 원본 행을 비관적 락(`lockExecutionWithVersion`)으로 읽어 동시 요청이 재처리를 둘 만드는 것을 막고, 큐 투입은 `afterCommit`에서 한다(워커가 링크를 읽어야 스킵 대상을 안다).

## config/ — Provider 포트 실구현
workflow-core가 선언한 포트 10개를 여기서 `Default*`로 구현해 Stub을 대체한다 (`@Primary`).
`DefaultCredentialProvider`, `DefaultGoogleTokenProvider`, `DefaultNotionTokenProvider`, `DefaultGitHubTokenProvider`, `DefaultWebhookCredentialProvider`, `DefaultMcpCatalogProvider`, `DefaultUserRoleProvider`, `DefaultBetaPlatformProvider`, `DefaultIdempotencyStore`, `DefaultAlertNotifier`.

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
| `ieum.workflow.queue.reclaim-min-idle` | `PT10M` | 이 시간 넘게 방치된 pending만 회수. **0으로 두지 말 것** — 살아 있는 소비자의 in-flight를 훔치지 않는 유일한 안전장치이자, 뒤집으면 재시작 복구 지연 상한이다. 워크플로우 최대 소요시간보다 넉넉히 길어야 한다 |
| `ieum.workflow.queue.reclaim-interval` | `PT1M` | 고아 잡 회수 주기 |
| `ieum.workflow.queue.poll-error-backoff` | `PT5S` | 폴링 실패 시 대기(스핀·로그 폭주 방지) |
| `ieum.beta.platform-key.allowed-models` | `gemini-3.5-flash` | platform 모드에서 재시도 모델 fallback이 시도할 수 있는 모델. platform 키는 Gemini 한 장이라 비-Gemini를 넣지 말 것 |
| `DISCORD_OPS_WEBHOOK_URL` | (빈 문자열) | 운영자 실패 알림 채널. `@Value`로 직접 읽는 환경변수 — 비어 있으면 발신 안 함 |

큐 관련 세 키는 yml에 선언돼 있지 않고 `ExecutionJobQueueBootstrap` 생성자의 `@Value` 기본값이다. `allowed-models`는 `application.yml`(`BETA_ALLOWED_MODELS`) + `BetaPlatformKeyProperties`.

## 공통
- `IeumApplication` — `@SpringBootApplication(scanBasePackages = "com.ieum")`. 누락 시 다른 모듈 Bean 스캔 안 됨
- `common/GlobalExceptionHandler` — `@RestControllerAdvice`, CustomException/MethodArgumentNotValid/일반 Exception 처리

## 크로스레포 계약 (ieum-agent 위임)
- 크레덴셜 헤더: `X-LLM-Provider`, `X-LLM-Api-Key`
- 멱등성 헤더: 부수효과가 있는 HTTP·AI 노드는 `retry.idempotency` 선언이 없어도 HEADER가 기본이라, 재시도가 켜져 있으면 agent에 `X-Idempotency-Key`를 보낸다(IEUM-BE-54. HTTP 노드는 외부 API에 표준 `Idempotency-Key`). agent가 이 헤더를 소비하며(IEUM-AI-52) 같은 키의 재요청은 도구를 다시 돌리지 않는다 — 소비하지 않는 배포에선 무시될 뿐이라 배포 순서 무관
- 베타 플랫폼 키 모드: `X-Key-Mode: platform` (소문자, ADMIN·TESTER에겐 미전송)
- agent `/v1/execute`·`/v1/chat` 응답 모두 usage 있음(IEUM-AI-48) → chat도 일일 호출 캡 + 토큰 예산 둘 다 적용. 차감 기준은 `usage.totalTokens`(입출력 합산 아님)
