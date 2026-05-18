# ieum-backend Style Guide

## 프로젝트 개요
Java 21 + Spring Boot 3.5.11 기반 멀티모듈 Gradle 모노리스.
AI 에이전트 기반 업무 자동화 플랫폼 (Zapier/Make + AI Agent).

---

## 모듈 구조 및 의존 방향

단방향 의존만 허용. 역방향 및 순환 참조 금지.

```
api          → workflow-core, auth
workflow-core → integration, ai
integration  → auth
모든 모듈     → common
```

- `common` 모듈은 다른 모듈을 참조하지 않는다
- `workflow-core`는 `api`, `auth`를 참조하지 않는다
- `auth`는 `workflow-core`, `ai`를 참조하지 않는다
- 모듈 경계를 넘는 직접 클래스 참조는 금지한다
- `api` 모듈만 `org.springframework.boot` 플러그인을 적용한다

---

## 레이어 아키텍처

### Controller
- 요청/응답 변환만 담당하며 비즈니스 로직을 포함하지 않는다
- 모든 응답은 `ApiResponse<T>` 래퍼로 감싼다 (`{ success, data, message }`)
- Swagger `@Tag` + `@Operation` 어노테이션을 명시한다
- `{Domain}ControllerDocs` 인터페이스를 분리하여 Swagger 문서와 구현을 분리한다

### Service
- `@Transactional(readOnly = true)`를 클래스 기본값으로 설정한다
- 쓰기 작업에만 `@Transactional`을 메서드 단위로 오버라이드한다
- 트랜잭션 경계를 명확히 책임진다

### Repository
- **커맨드용** `{Entity}Repository`: `JpaRepository` 상속 — 저장/삭제/PK 단건 조회만
- **쿼리용** `{Entity}QueryRepository`: `@Repository` 클래스, `JPAQueryFactory` 주입 — 복잡한 조회
- `orderBy()`에 사용자 입력값을 직접 전달하지 않는다 — whitelist 필드만 허용

---

## 엔티티 (JPA)

- 모든 엔티티는 `BaseEntity`를 상속한다 (UUID id, createdAt, updatedAt 자동 관리)
- `@NoArgsConstructor(access = PROTECTED)`를 사용한다 (직접 인스턴스화 금지)
- `FetchType.LAZY`를 기본으로 사용한다 (`EAGER` 금지)
- `@Enumerated`는 반드시 `EnumType.STRING`을 사용한다 (`ORDINAL` 금지)
- N+1 문제 방지를 위해 `@EntityGraph` 또는 fetch join을 사용한다
- 벌크 연산 후에는 `@Modifying(clearAutomatically = true)`로 영속성 컨텍스트를 초기화한다

---

## DTO

- Response DTO는 `from(Entity)` 정적 팩토리 메서드를 제공한다
- 페이징 응답은 `PageResponse<T>` 래퍼를 사용한다

---

## 예외 처리

- 비즈니스 예외는 반드시 `CustomException(ErrorCode)`를 사용한다
- `RuntimeException`을 직접 throw하는 것을 금지한다
- 글로벌 예외 처리는 `@RestControllerAdvice`를 단 하나만 유지한다
- JWT 예외는 반드시 `CustomException`으로 변환한다:
  - `ExpiredJwtException` → `TOKEN_EXPIRED`
  - `JwtException` → `TOKEN_INVALID`
- `ErrorCode`에 새 코드 추가 시 카테고리 주석 아래에 그룹핑한다

---

## 보안

- API 키, Secret, OAuth 토큰을 코드에 하드코딩하지 않는다
- OAuth Token, AI API Key는 반드시 AES-256-GCM으로 암호화하여 DB에 저장한다 (평문 저장 금지)
- `node_runs`의 input/output JSONB에 자격증명(토큰, API Key)을 포함하지 않는다
- API Key는 `key_hint`만 조회 가능하며, 원문은 실행 시 서버 내부에서만 복호화한다
- 노드 config에 실제 토큰/키 값을 저장하지 않는다 — `credential_id` 참조만 허용
- 연동 해제 시 토큰을 즉시 DELETE한다 (소프트 딜리트 금지)
- 민감 정보는 `application.yml`이 아닌 환경변수(`.env`)로 주입한다

---

## 워크플로우 엔진 특화 규칙

- 변수 참조 문법: `{{nodes.<node_uuid>.output.<field>}}` (이 형식만 허용)
- 워크플로우당 트리거 노드는 1개만 허용한다 — 검증 로직 필수
- `NodeExecutor` 구현체는 `@Component`로 등록하고, Stub은 `@ConditionalOnMissingBean`으로 선언한다
- `ExecutorResult`는 빌더 패턴으로 생성한다

---

## 테스트

- 신규 서비스 메서드에는 단위 테스트를 작성한다
- `@SpringBootTest`는 통합 테스트에만 사용한다
- 단위 테스트는 `@ExtendWith(MockitoExtension.class)`를 사용한다
- 테스트 메서드명은 한글로 작성한다 (예: `크레덴셜_저장_성공`)

---

## 커밋 컨벤션

- 형식: `type: 내용 (한글 작성)`
- type 목록: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `infra`
- 예시: `feat: 워크플로우 실행 커서 구현`

