# Auth Module Context

## 역할
JWT 인증/인가, OAuth2 연동 (Google·Notion·GitHub), 사용자 관리를 담당한다.
OAuth 로그인은 Google만, Notion·GitHub는 연동(커넥터 토큰) 용도다.

## 핵심 구현 사항
- **JWT**: Access Token (30분) + Refresh Token (Redis, 7일)
- **SecurityConfig**: Stateless 세션, JwtAuthenticationFilter를 UsernamePasswordAuthenticationFilter 앞에 등록
- **OAuth2**: Google OAuth 로그인 → 자동 회원가입 + JWT 발급
- **비밀번호**: BCryptPasswordEncoder
- **인가**: `@EnableMethodSecurity`로 메서드 레벨 권한 체크

## 이미 구현된 클래스
- `SecurityConfig` — 필터 체인, 공개 경로 설정, CORS/CSRF/세션 비활성화
- `RedisRepositoryConfig` — Redis 연결 설정
- `JwtTokenProvider` — 토큰 생성/검증/파싱
- `JwtAuthenticationFilter` — 요청별 JWT 검증 (OncePerRequestFilter)
- `JwtAuthenticationEntryPoint` — 401 응답 처리
- `DelegationTokenService` / `DelegationTokenFilter` (`delegation/`, IEUM-BE-106) — WS 채팅 한 턴 동안 agent가 사용자 대신 BE를 부르는 불투명 토큰. 원문 32바이트 base64url, Redis `delegation:{sha256 hex}` = `{userId, role, workflowId}` EX 600(`@RedisHash` 금지 — keyspace events 꺼짐), 발급·폐기는 api `WebSocketChatHandler`. 필터는 `X-Delegation-Token` 헤더가 있을 때만, `JwtAuthenticationFilter` **앞**. 헤더가 공백이거나 토큰의 role이 비면 401(조회 전에 거른다). 허용(메서드+경로) 5개 — `GET /api/v1/integrations/{app}/options/{resource}`·`GET …/fields/{resource}`·`GET /api/v1/oauth/connections`(토큰 userId)·`POST /api/v1/workflows/{workflowId}/nodes/{nodeId}/test`·`GET …/sample`(경로 workflowId = 토큰 것, 아니면 403) — 밖이면 401. 통과하면 `CustomUserDetails.of(userId, "", role)`(이메일 없음 — 대상 컨트롤러는 `getId()`만 쓴다). 허용 경로를 늘릴 때는 그 컨트롤러가 `getUsername()`에 기대지 않는지 먼저 본다
- `CustomUserDetails` / `CustomUserDetailsService` — Spring Security UserDetails 구현
- `User` 엔티티 — email, passwordHash, name, provider, providerId, role, **betaAccess**(베타 초대 여부, role과 직교)
- `UserRole` enum — ROLE_USER, ROLE_ADMIN, **ROLE_TESTER** (ADMIN·TESTER는 self-hosted LLM 라우팅 대상)
- `AuthProvider` enum — LOCAL, GOOGLE, NOTION, GITHUB
- `ConnectedAccount` — 연동 계정별 OAuth 토큰 (AES-256 암호화)
- `SlackInstallation` / `SlackUserLink` — Slack 워크스페이스 설치(팀당 1행, 봇 토큰 AES-256) / 사용자↔설치 링크(사용자당 1개, 여럿이 한 설치 공유)
- `DiscordGuildLink` — 사용자↔Discord 서버 링크((사용자, 서버)당 1행, 봇 토큰은 앱당 하나(env)라 저장 토큰 없음, `guild_name`은 연결 시점 이름)
- `RefreshToken` — Redis Hash 저장 객체
- `OAuthAuthorizationCode` / `OAuthLinkToken` / `NotionOAuthState` / `GitHubOAuthState` / `SlackOAuthState` / `DiscordOAuthState` — OAuth 플로우 단기 상태(Discord는 브라우저 nonce 쿠키의 SHA-256만 저장)
- `TokenInfo` DTO — accessToken, refreshToken 쌍
- `AuthService` — 회원가입, 로그인, 토큰 갱신 로직
- `GoogleOAuthService` / `GoogleTokenService` / `GitHubTokenService` — OAuth 인증·토큰 갱신
- `SlackTokenService` / `SlackApiClient` — Slack 봇 토큰 조회·갱신(REQUIRES_NEW, 만료 임박이면 설치 행 락 뒤 회전) / Slack Web API 호출·revoke
- `DiscordApiClient` — Discord HTTP API v10 호출(code 교환·사용자·서버·채널·봇 퇴장·revoke). 실패는 `RestClientException` 그대로 던지고 판정은 호출자, 429만 1회 재시도
- `UserRepository` — JpaRepository (커맨드용)
- `UserRepositoryCustom` / `UserRepositoryImpl` — QueryDSL 기반 조회용
- `RefreshTokenRepository` — Redis CrudRepository
- `SlackInstallationRepository`(`findByTeamIdForUpdate`·`findByIdForUpdate` 행 락) / `SlackUserLinkRepository` / `SlackOAuthStateRepository`
- `DiscordGuildLinkRepository`(`countByGuildId` — 해제 뒤 마지막 링크 판정) / `DiscordOAuthStateRepository`

## 공개 경로 (인증 불필요)
- `/api/v1/auth/**`, `/api/v1/providers`
- `/swagger-ui/**`, `/v3/api-docs/**`
- `/api/v1/hooks/**`(URL 토큰이 인증), `/api/v1/integrations/github/webhook`(HMAC 서명), `/actuator/**`

## 주의사항
- JWT 예외는 반드시 `CustomException(ErrorCode.TOKEN_EXPIRED)` 또는 `CustomException(ErrorCode.TOKEN_INVALID)`로 변환
- RefreshToken은 Redis에 저장 — RDB에 저장하지 않음
- OAuth2 토큰(access_token, refresh_token)은 AES-256 암호화 후 `connected_accounts` 테이블에 저장
- Google OAuth 콜백 후 JWT를 프론트엔드 리다이렉트 URL에 쿼리 파라미터로 전달
- 연동 해제 시 access_token/refresh_token 즉시 DELETE — 소프트 딜리트 금지

## 패키지 구조
```
com.ieum.auth
├── config/      # SecurityConfig, RedisRepositoryConfig
├── domain/      # User, UserRole, AuthProvider, ConnectedAccount, RefreshToken, OAuth 상태 객체
├── dto/         # TokenInfo
├── jwt/         # JwtTokenProvider, JwtAuthenticationFilter, JwtAuthenticationEntryPoint
├── repository/  # UserRepository, UserRepositoryCustom, UserRepositoryImpl, RefreshTokenRepository
├── security/    # CustomUserDetails, CustomUserDetailsService
└── service/     # AuthService, GoogleOAuthService, GoogleTokenService, GitHubTokenService
```
