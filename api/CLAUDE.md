# API Module Context

## 역할
메인 진입점 (SpringBoot Application). REST Controller, DTO, Swagger 설정을 담당한다.
유일하게 `org.springframework.boot` 플러그인이 적용된 실행 가능한 모듈이다.

## 핵심 규칙
- Controller는 반드시 Swagger 문서화: `@Tag` (클래스), `@Operation` (메서드)
- Docs 인터페이스 분리 패턴: `{Domain}ControllerDocs` 인터페이스에 Swagger 어노테이션 선언, Controller가 이를 구현
- 모든 응답은 `ApiResponse<T>` 래핑
- Request DTO에 `@Valid` + Bean Validation 어노테이션 (`@NotBlank`, `@Email` 등)
- Response DTO에 `from(Entity)` 정적 팩토리 메서드
- 비즈니스 로직은 Controller에 두지 않음 — Service에 위임

## 이미 구현된 클래스
- `IeumApplication` — `@SpringBootApplication(scanBasePackages = "com.ieum")`
- `AuthController` + `AuthControllerDocs` — 회원가입, 로그인, 토큰 갱신
- `UserController` + `UserControllerDocs` — 내 정보 조회/수정
- `CredentialController` + `CredentialControllerDocs` — AI API Key CRUD, 검증
- `ProviderController` — 지원 프로바이더 목록 조회
- `GlobalExceptionHandler` — `@RestControllerAdvice`, CustomException/MethodArgumentNotValid/일반 Exception 처리
- `JpaConfig` — JPA 관련 설정
- `SwaggerConfig` — OpenAPI 3.0 설정, JWT Bearer 인증 스키마

## DTO 목록
- Auth: `LoginRequest`, `RegisterRequest`, `RefreshRequest`, `RegisterResponse`, `TokenResponse`
- User: `UpdateUserRequest`, `UserResponse`
- Credential: `CreateCredentialRequest`, `CredentialResponse`, `ValidateCredentialResponse`
- Provider: `ProviderListResponse`

## scanBasePackages
`scanBasePackages = "com.ieum"` → 모든 모듈(auth, ai, workflow-core, integration, common)의 Bean을 자동 스캔

## 패키지 구조
```
com.ieum.api
├── IeumApplication.java
├── auth/
│   ├── controller/  # AuthController, AuthControllerDocs
│   └── dto/         # LoginRequest, RegisterRequest, RefreshRequest, RegisterResponse, TokenResponse
├── credential/
│   ├── controller/  # CredentialController, CredentialControllerDocs
│   └── dto/         # CreateCredentialRequest, CredentialResponse, ValidateCredentialResponse
├── provider/
│   ├── controller/  # ProviderController
│   └── dto/         # ProviderListResponse
├── user/
│   ├── controller/  # UserController, UserControllerDocs
│   ├── dto/         # UpdateUserRequest, UserResponse
│   └── service/     # UserService
├── common/          # GlobalExceptionHandler
└── config/          # JpaConfig, SwaggerConfig
```
