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
| `workflow` | WorkflowController, WorkflowDashboardController | 워크플로우 CRUD·실행·실행 이력 조회·SSE 진행 스트림, 대시보드 요약/최근실행/에러 |
| `chat` | ChatController | 워크플로우 채팅 (+ `AgentClient`가 ieum-agent 호출, WebSocket 핸들러) |
| `webhook` / `webhookcredential` | WebhookController, WebhookCredentialController | 웹훅 트리거 수신·웹훅 크레덴셜 |
| `integration` | IntegrationWorkflowController | 연동 서비스별 워크플로우 조회 |
| `mcp` | McpServerCatalogController | MCP 서버 카탈로그 |
| `prompt` | PromptTemplateController | 프롬프트 템플릿 CRUD·테스트 실행 |
| `beta` | BetaUsageController | 베타 플랫폼 키 사용량(%) 조회 |

## 실행 트리거
`workflow/WorkflowExecutionRunner` — 실행 진입점. Redis Stream 잡 큐(`ieum:exec:jobs`)에 executionId만 발행하고, 같은 프로세스의 워커가 꺼내 `SyncExecutionRuntime`을 돌린다. 큐 발행이 실패하면(Redis 장애) 기존 `@Async` 직접 실행으로 폴백한다. 실행 레코드 생성 자체는 workflow-core `WorkflowExecutionService.prepareExecution()`.

`workflow/queue/` — `ExecutionJobQueue`(발행), `ExecutionJobWorker`(소비), `ExecutionJobQueueBootstrap`(그룹 생성·소비 시작·고아 잡 주기 회수), `ExecutionJobQueueConfig`(컨테이너 빈).
**워커를 별도 프로세스로 빼지 말 것** — `ExecutionEventPublisher`가 in-memory `Sinks.Many`라 SSE가 즉시 깨진다. 큐의 목적은 수평 확장이 아니라 재시작 복구다.
**단일 인스턴스 배포(stop-then-start) 전제** — 컨슈머 이름이 상수라 인스턴스를 구분하지 않는다. 롤링 배포로 두 인스턴스가 겹치면 고아 잡 회수가 살아 있는 쪽의 in-flight를 뺏어 이중 실행할 수 있다(`ieum.workflow.queue.reclaim-min-idle`, 기본 10분이 유일한 안전장치). 스케일아웃은 SSE 허브 교체가 선행 조건.
Quartz 스케줄 실행(`WorkflowScheduleJob`)은 큐를 거치지 않아 내구성이 없다 — Provider 포트로 뒤집으면 해결되나 별도 이슈.

## config/ — Provider 포트 실구현
workflow-core가 선언한 포트를 여기서 `Default*`로 구현해 Stub을 대체한다 (`@Primary`).
`DefaultCredentialProvider`, `DefaultGoogleTokenProvider`, `DefaultNotionTokenProvider`, `DefaultGitHubTokenProvider`, `DefaultWebhookCredentialProvider`, `DefaultMcpCatalogProvider`, `DefaultUserRoleProvider`, `DefaultBetaPlatformProvider`.

기타 설정: `JpaConfig`, `SwaggerConfig`, `RestTemplateConfig`, `WebSocketConfig`, `WebSocketAuthInterceptor`.

## 공통
- `IeumApplication` — `@SpringBootApplication(scanBasePackages = "com.ieum")`. 누락 시 다른 모듈 Bean 스캔 안 됨
- `common/GlobalExceptionHandler` — `@RestControllerAdvice`, CustomException/MethodArgumentNotValid/일반 Exception 처리

## 크로스레포 계약 (ieum-agent 위임)
- 크레덴셜 헤더: `X-LLM-Provider`, `X-LLM-Api-Key`
- 베타 플랫폼 키 모드: `X-Key-Mode: platform` (소문자, ADMIN·TESTER에겐 미전송)
- agent `/v1/execute`·`/v1/chat` 응답 모두 usage 있음(IEUM-AI-48) → chat도 일일 호출 캡 + 토큰 예산 둘 다 적용. 차감 기준은 `usage.totalTokens`(입출력 합산 아님)
