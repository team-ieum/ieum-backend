# Auth Module Context

## 역할
JWT 인증/인가, OAuth2 소셜 로그인 (Google), 사용자 관리를 담당한다.

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
- `CustomUserDetails` / `CustomUserDetailsService` — Spring Security UserDetails 구현
- `User` 엔티티 — email, password, name, role, authProvider
- `UserRole` enum — ROLE_USER, ROLE_ADMIN
- `AuthProvider` enum — LOCAL, GOOGLE
- `RefreshToken` — Redis Hash 저장 객체
- `TokenInfo` DTO — accessToken, refreshToken 쌍
- `AuthService` — 회원가입, 로그인, 토큰 갱신 로직
- `UserRepository` — JpaRepository (커맨드용)
- `UserRepositoryCustom` / `UserRepositoryImpl` — QueryDSL 기반 조회용
- `RefreshTokenRepository` — Redis CrudRepository

## 공개 경로 (인증 불필요)
- `/api/v1/auth/**`, `/api/v1/providers`
- `/swagger-ui/**`, `/v3/api-docs/**`
- `/webhooks/**`, `/actuator/**`

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
├── domain/      # User, UserRole, AuthProvider, RefreshToken
├── dto/         # TokenInfo
├── jwt/         # JwtTokenProvider, JwtAuthenticationFilter, JwtAuthenticationEntryPoint
├── repository/  # UserRepository, UserRepositoryCustom, UserRepositoryImpl, RefreshTokenRepository
├── security/    # CustomUserDetails, CustomUserDetailsService
└── service/     # AuthService
```
