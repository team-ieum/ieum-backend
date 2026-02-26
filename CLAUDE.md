# ieum-backend — Claude Context

## 프로젝트 개요
`ieum-backend`는 Zapier/Make 같은 **범용 업무자동화 플랫폼 (IEUM)** 의 백엔드입니다.
AI 에이전트가 상황을 판단하고 실행하는 스마트 자동화 플랫폼으로, Java 21 + Spring Boot 3.x 기반의 **멀티모듈 Gradle 모듈러 모노리스** 구조입니다.

## 모듈 구조
| 모듈 | 역할 | 주요 의존성 |
|------|------|------------|
| `api` | 메인 진입점, REST API (SpringBoot 실행 모듈) | auth, workflow-core, springdoc, validation |
| `auth` | 인증/인가 (JWT + OAuth2) | common, spring-security, jjwt, oauth2-client |
| `workflow-core` | 워크플로우 엔진 (핵심 비즈니스 로직) | common, integration, ai, quartz |
| `ai` | AI 연동 모듈 (Claude, OpenAI 등) | common, integration, JPA |
| `integration` | 외부 서비스 연동 (Google, Notion 커넥터) | common, auth, redis |
| `common` | 공통 유틸, 예외, 상수, BaseEntity 등 | spring-boot-starter |

### 모듈 간 의존 방향 (단방향 유지 필수, 순환 참조 금지)
```
api → workflow-core, auth
workflow-core → integration, ai
integration → auth
```

## 기술 스택
- **Java 21** (LTS), **Spring Boot 3.5.11**
- **Gradle 8.14.4** (멀티모듈, Groovy DSL)
- **PostgreSQL 16** — 메인 DB (UUID PK, JSONB, TIMESTAMP)
- **Redis 7** — 캐시/세션
- **Flyway** — DB 마이그레이션
- **Lombok** — 전 모듈 공통 적용
- **Spring Security + OAuth2** — Google OAuth 연동
- **JWT** (jjwt 0.12.6) — Access Token + Refresh Token
- **AES-256** — OAuth 토큰 및 AI API Key 암호화 저장
- **Quartz** — 스케줄러 (workflow-core)
- **springdoc-openapi 2.8.4** — Swagger UI (api 모듈)
- **Spring Validation** — 요청 검증
- **Spring @Async** (MVP) → RabbitMQ (확장 시)

## DB 스키마 (MVP 9개 테이블)
| 테이블 | 설명 | 소속 모듈 |
|--------|------|----------|
| `users` | 사용자 (자체 로그인 + 소셜) | auth |
| `connected_accounts` | OAuth 연동 토큰 (Google, Notion) — AES-256 암호화 | auth / integration |
| `user_ai_credentials` | 사용자 AI API Key (Claude, OpenAI 등) — AES-256 암호화 | ai |
| `workflows` | 워크플로우 정의 | workflow-core |
| `workflow_versions` | 워크플로우 버전 (nodes/edges JSONB) | workflow-core |
| `workflow_runs` | 워크플로우 실행 이력 | workflow-core |
| `node_runs` | 노드별 실행 결과 (input/output JSONB) | workflow-core |
| `notifications` | 알림 내역 (웹 + 이메일) | api |
| `notification_settings` | 사용자별 알림 수신 설정 | api |

### 보안 규칙
- OAuth Token, AI API Key는 **AES-256 암호화** 후 DB 저장. 평문 저장 절대 금지
- `node_runs`의 `input`/`output` JSONB에 자격증명 포함 금지 — 저장 전 반드시 필터링
- API Key 등록 후 `key_hint`만 조회 가능. 원문은 워크플로우 실행 시 서버 내부에서만 복호화
- 연동 해제 시 access_token/refresh_token 즉시 DELETE. 소프트 딜리트 금지

## API 설계 규칙
- Base URL: `/api/v1`
- 인증: JWT (Authorization: Bearer {access_token})
- 공통 응답 포맷: `{ "success": boolean, "data": object|null, "message": string }`
- 주요 도메인: 인증, 사용자, 연동 계정, 워크플로우(CRUD+버전+실행), 알림

## 노드 시스템
워크플로우는 노드와 엣지로 구성됩니다. 변수 치환 문법: `${node_id.field}`

### 노드 타입
| 카테고리 | 타입 | 설명 |
|---------|------|------|
| 트리거 | `webhook_trigger`, `schedule_trigger`, `gmail_trigger`, `sheets_trigger`, `notion_trigger` | 워크플로우 시작점 (워크플로우당 1개) |
| 액션 | `gmail_action`, `sheets_action`, `drive_action`, `calendar_action`, `notion_action` | 외부 서비스 실행 |
| AI | `ai_summarize`, `ai_classify`, `ai_extract` | AI 기반 텍스트 처리 |
| 로직 | `condition`, `delay`, `error_handler` | 분기/대기/에러 처리 |

### AI 노드 자격증명
- `credential_type: "api_key"` → `user_ai_credentials.id` 참조
- `credential_type: "oauth"` → `connected_accounts.id` 참조
- config에 실제 토큰/키 값 절대 포함 금지

## 로컬 개발 환경
```bash
# 인프라 실행
docker-compose up -d   # PostgreSQL 16 + Redis 7

# 빌드
./gradlew build

# 실행 (api 모듈, 로컬 프로필)
./gradlew :api:bootRun --args='--spring.profiles.active=local'
```

### DB 접속 정보 (로컬)
- Host: `localhost:5432`, DB: `ieum`, User/PW: `ieum`/`ieum`

## 코드 컨벤션
- 패키지 루트: `com.ieum.<모듈명>`
- 레이어 구조: `controller` → `service` → `repository`
- DTO: Lombok `@Builder`, `@Getter`, Response에 `from()` 정적 팩토리 메서드
- 엔티티: `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `FetchType.LAZY` 기본
- Enum: `EnumType.STRING` 사용 (ORDINAL 금지)
- 예외: common 모듈의 `IeumException` + `ErrorCode` enum
- API 응답: 공통 래퍼 `{ success, data, message }`
- Service: `@Transactional(readOnly = true)` 기본, 쓰기는 메서드에 `@Transactional`
- Controller: Swagger `@Tag`, `@Operation` 추가

## Git 컨벤션
- 브랜치: `main` → `develop` → `feature/<기능명>` (직접 push는 feature만)
- 브랜치명: 영어 소문자 + 하이픈(-) 만 사용
- 커밋: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`
- PR: `[feat] 기능명` 형식, Squash Merge만 사용
- feature 브랜치 수명: 최대 3일

## DB 마이그레이션
- 위치: `api/src/main/resources/db/migration/`
- 파일명: `V{버전}__{설명}.sql` (언더바 2개)

## 주의사항
- `api` 모듈만 `org.springframework.boot` 플러그인 적용 (실행 가능한 JAR)
- `application-local.yml`은 Git 추적 제외 (로컬 전용)
- `scanBasePackages = "com.ieum"` 으로 모든 모듈 Bean 스캔
- 공통 의존성(Lombok, Test)은 루트 `build.gradle`의 `subprojects`에 선언 → 중복 선언 불필요

## 자주 쓰는 Gradle 명령
```bash
./gradlew :api:bootRun                    # API 실행
./gradlew :<모듈명>:build                  # 특정 모듈 빌드
./gradlew test                            # 전체 테스트
./gradlew :api:dependencies               # 의존성 확인
```

## Skills

커스텀 스킬은 `.claude/skills/`에 정의되어 있습니다.

| Skill | Purpose |
|-------|---------|
| `api-endpoint` | REST API 엔드포인트 생성 패턴 (Controller/Service/Repository/DTO) |
| `jpa-entity` | JPA 엔티티 설계 패턴 (PostgreSQL 기반) |
| `exception-handling` | 공통 예외 처리 패턴 (IeumException + ErrorCode) |
| `ai-integration` | AI 모듈 연동 패턴 (Claude, OpenAI 등) |
| `new-module` | Gradle 서브모듈 추가 절차 |
| `verify-implementation` | 프로젝트의 모든 verify 스킬을 순차 실행하여 통합 검증 보고서를 생성합니다 |
| `manage-skills` | 세션 변경사항을 분석하고, 검증 스킬을 생성/업데이트하며, CLAUDE.md를 관리합니다 |

## 참고 문서 (Notion)
- 프로젝트 설계 정리: 아키텍처, MVP 범위, 로드맵
- DB 스키마 설계: 테이블 상세, 제약조건, 보안 규칙
- 노드 인터페이스 스펙: 노드 타입별 config/output 스키마
- API 스펙 초안: 전체 엔드포인트 목록 및 요청/응답 형식
- Git 브랜치 전략: 브랜치/커밋/PR/머지 규칙
- Spring Boot 프로젝트 세팅 가이드: 모듈별 build.gradle, application.yml
