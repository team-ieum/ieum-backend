# Common Module Context

## 역할
전 모듈이 공유하는 공통 유틸, 예외, 상수, BaseEntity를 제공한다.

## 이미 구현된 클래스 (재생성 금지)
- `ErrorCode` — enum, HttpStatus + message. 카테고리별 그룹핑 (공통/인증/크레덴셜/워크플로우/AI 에이전트/연동)
- `CustomException` — RuntimeException 래퍼, `ErrorCode` 필드
- `SuccessCode` — 성공 응답 코드
- `ApiResponse<T>` — 공통 API 응답 래퍼 `{ success, data, message }`, `ok()` / `error()` 정적 메서드
- `PageResponse<T>` — 페이징 응답 래퍼
- `BaseEntity` — id(UUID), createdAt, updatedAt 자동 관리 (`@MappedSuperclass`)
- `JpaAuditingConfig` — `@EnableJpaAuditing`
- `QueryDslConfig` — `JPAQueryFactory` Bean 등록
- `AesEncryptor` — AES-256 암/복호화 유틸 (OAuth 토큰, AI API Key 암호화용)

## 새 ErrorCode 추가 규칙
- 카테고리 주석(`// 공통`, `// 인증` 등) 아래에 그룹핑하여 추가
- HttpStatus는 의미에 맞게 정확히 지정
- message는 한글, 사용자에게 노출 가능한 수준으로 작성

## 패키지 구조
```
com.ieum.common
├── config/      # JpaAuditingConfig, QueryDslConfig
├── dto/         # ApiResponse, PageResponse
├── entity/      # BaseEntity
├── exception/   # CustomException, ErrorCode, SuccessCode
└── util/        # AesEncryptor
```

## 주의사항
- 다른 모듈(auth, ai 등)에 대한 의존성을 가지면 안 됨 — 단방향 의존 원칙
- Lombok은 루트 build.gradle의 subprojects에 공통 선언되어 있음 — 중복 선언 불필요
