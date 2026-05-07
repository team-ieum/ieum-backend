# ieum-backend — Claude Context

## 프로젝트 개요
AI 에이전트 기반 업무 자동화 플랫폼 (IEUM)의 백엔드. Zapier/Make + AI Agent를 결합한 B2B/B2G 솔루션.
Java 21 + Spring Boot 3.5.11 기반의 **멀티모듈 Gradle 모듈러 모노리스** 구조.

## 현재 상태
- **Phase 1 (Auth)** 구현 완료 — 회원가입, 로그인, JWT, Google OAuth
- **Phase 2 (Credential + Dashboard)** 구현 완료 — AI API Key BYOK, 프로바이더 관리
- **Phase 3 (Workflow Engine)** 다음 착수 대상
- 각 모듈의 상세 구현 현황은 해당 모듈의 `CLAUDE.md` 참조

## 모듈 구조

| 모듈 | 역할 | 상세 |
|------|------|------|
| `api` | REST API 진입점 (유일한 SpringBoot 실행 모듈) | `api/CLAUDE.md` |
| `auth` | 인증/인가 (JWT + OAuth2) | `auth/CLAUDE.md` |
| `workflow-core` | 워크플로우 엔진 (핵심 비즈니스) | `workflow-core/CLAUDE.md` |
| `ai` | AI 프로바이더 연동 + 크레덴셜 BYOK | `ai/CLAUDE.md` |
| `integration` | 외부 서비스 커넥터 (Google, Notion) | `integration/CLAUDE.md` |
| `common` | 공통 예외, DTO, BaseEntity, 유틸 | `common/CLAUDE.md` |

### 의존 방향 (단방향 필수, 순환 참조 금지)
```
api → workflow-core, auth
workflow-core → integration, ai
integration → auth
모든 모듈 → common
```

## 기술 스택
Java 21, Spring Boot 3.5.11, Gradle 8.14.4 (Groovy DSL), PostgreSQL 16 (UUID PK, JSONB),
Redis 7, Flyway, Lombok, Spring Security + OAuth2, JWT (jjwt 0.12.6), AES-256 암호화,
QueryDSL (openfeign 7.1), springdoc-openapi 2.8.4, Quartz, Spring @Async (MVP)

## Claude Code 워크플로우 규칙

### 행동 지침
1. **git 명령 실행 금지** — `git commit`, `git push`, `git checkout` 등 모든 git 명령은 안내만 하고 DoHoon이 직접 실행
2. **커밋 단위로 멈추기** — 한 커밋 분량의 작업 완료 후 반드시 멈추고 다음 진행 여부 확인
3. **환경변수는 `.env` 파일** — `application-local.yml`에 작성 금지
4. **모듈 CLAUDE.md 먼저 읽기** — 해당 모듈 작업 전 반드시 모듈 CLAUDE.md를 읽고 이미 구현된 클래스 확인
5. **이미 구현된 것 재생성 금지** — ErrorCode, CustomException, ApiResponse, PageResponse, BaseEntity, AesEncryptor 등

### 구현 순서 원칙
엔티티+Repository → Service → DTO → Controller → Flyway 마이그레이션 → 설정 → 테스트

### 각 단계 완료 시 출력 형식
```
✅ Step {N}/{총} 완료: {커밋 메시지}
📁 생성/수정 파일 목록
💡 git 명령어 (직접 실행)
다음 단계 진행할까요?
```

## 코드 컨벤션

### 공통
- 패키지: `com.ieum.<모듈명>`, 레이어: `controller` → `service` → `repository`
- 예외: `CustomException(ErrorCode)` 사용 — RuntimeException 직접 throw 금지
- API 응답: `ApiResponse<T>` 래퍼 `{ success, data, message }`
- Service: `@Transactional(readOnly = true)` 기본, 쓰기만 `@Transactional`
- Controller: Swagger `@Tag` + `@Operation`, `{Domain}ControllerDocs` 인터페이스 분리

### 엔티티
- `BaseEntity` 상속 (UUID id, createdAt, updatedAt)
- `@NoArgsConstructor(access = PROTECTED)`, `FetchType.LAZY` 기본, `EnumType.STRING` (ORDINAL 금지)
- Response DTO에 `from(Entity)` 정적 팩토리 메서드

### Repository
- 커맨드용: `{Entity}Repository` — JpaRepository 상속 (저장/삭제/PK 조회)
- 쿼리용: `{Entity}QueryRepository` — `@Repository` 클래스, JPAQueryFactory 주입 (복잡한 조회)
- `orderBy()`에 사용자 입력값 직접 전달 금지 — whitelist 필드만 허용

### 보안
- OAuth Token, AI API Key → AES-256 암호화 후 DB 저장, 평문 저장 금지
- `node_runs` JSONB에 자격증명 포함 금지
- API Key는 `key_hint`만 조회 가능, 원문은 실행 시 서버 내부에서만 복호화
- 연동 해제 시 토큰 즉시 DELETE, 소프트 딜리트 금지

## Git 컨벤션
- 브랜치: `main` → `develop` → `{type}/IEUM-BE-{번호}` (Notion 이슈 ID 기반)
- 커밋: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`
- PR: `[feat] 기능명`, Squash Merge만 사용, feature 브랜치 수명 최대 3일
- PR 설명에 Notion 이슈 링크 포함

## Gotchas (자주 빠지는 함정)
- `api` 모듈만 `org.springframework.boot` 플러그인 적용 — 다른 모듈에 적용하면 빌드 실패
- `scanBasePackages = "com.ieum"` 누락 시 다른 모듈 Bean 스캔 안 됨
- 공통 의존성(Lombok, Test)은 루트 `build.gradle`의 `subprojects`에 선언 → 모듈별 중복 선언 불필요
- Q클래스는 `./gradlew build` 후 생성됨 — 빌드 전에는 QueryDSL 코드가 컴파일 에러
- `application-local.yml`은 `.gitignore`에 포함 — 환경변수는 `.env` 파일 사용
- Flyway 파일명: `V{버전}__{설명}.sql` (언더바 **2개**), 위치: `api/src/main/resources/db/migration/`
- 변수 참조 문법: `{{nodes.<uuid>.output.<field>}}` (workflow-core에서 사용)
- JWT 예외는 반드시 CustomException으로 변환 — ExpiredJwtException → TOKEN_EXPIRED, JwtException → TOKEN_INVALID

## 빌드 & 실행
```bash
docker-compose up -d                                    # PostgreSQL 16 + Redis 7
./gradlew build                                         # 전체 빌드 (Q클래스 생성 포함)
./gradlew :api:bootRun --args='--spring.profiles.active=local'  # API 실행
./gradlew :<모듈명>:build                                # 특정 모듈 빌드
./gradlew test                                          # 전체 테스트
```
로컬 DB: `localhost:5432`, DB/User/PW: `ieum`/`ieum`/`ieum`

## Skills
커스텀 스킬은 `.claude/skills/`에 정의. 자연어로 자동 활성화됨.

| Skill | Purpose |
|-------|---------|
| `api-endpoint` | REST API 엔드포인트 생성 패턴 (Controller/Service/Repository/DTO) |
| `jpa-entity` | JPA 엔티티 설계 패턴 (PostgreSQL 기반) |
| `exception-handling` | 공통 예외 처리 패턴 (CustomException + ErrorCode) |
| `ai-integration` | AI 모듈 연동 패턴 (Claude, OpenAI 등) |
| `new-module` | Gradle 서브모듈 추가 절차 |
| `notion-branch-bootstrap` | Notion 이슈 ID 기반 브랜치 준비, 영향 범위 분석, 커밋 계획 |
| `commit-unit-guide` | 구현 작업을 커밋 단위로 분리, 각 단계 후 멈춤 |
| `pr-readiness` | PR 전 빌드/품질/Notion 동기화 점검, PR 설명 초안 생성 |
| `verify-api-response` | API 응답 포맷 및 예외 처리 규칙 검증 |
| `verify-jpa-entity` | JPA 엔티티 및 Repository 규칙 검증 |
| `verify-db-migration` | Flyway 마이그레이션 규칙 검증 |
| `verify-security` | Spring Security 및 JWT 인증 규칙 검증 |
| `verify-workflow-engine` | 워크플로우 실행 엔진 패턴 규칙 검증 (NodeExecutor 등록, Stub 패턴, ExecutorResult 생성) |
| `verify-implementation` | 모든 verify 스킬 순차 실행, 통합 검증 보고서 |
| `manage-skills` | 세션 변경사항 분석, 스킬 생성/업데이트, CLAUDE.md 관리 |
