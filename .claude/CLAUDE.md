# ieum-backend

# Commands
- clone·worktree 직후 `git submodule update --init` — `catalog/`가 비면 `:workflow-core:catalogIndex`가 빌드·테스트·bootJar를 전부 멈춘다
- 의존 서비스 `docker compose up -d`, 실행 `./gradlew :api:bootRun --args='--spring.profiles.active=local'`
- 테스트 하나: `./gradlew :api:test --tests 'com.ieum.api.workflow.service.ApprovalGateServiceTest'`
- PG·Redis 실물 테스트(기본 skip): `IEUM_TEST_POSTGRES_URL`·`_USER`·`_PASSWORD`, `IEUM_TEST_REDIS_HOST`를 주고 실행
- 배포 스크립트 테스트: `python3 -m unittest discover -s scripts/bluegreen -p 'test_*.py'`
- QueryDSL Q클래스는 `./gradlew build` 후에 생긴다 — 그 전 컴파일 에러는 정상

# Conventions
- 모듈 작업 전 그 모듈 `CLAUDE.md`(api·workflow-core·auth·common)를 읽는다 — 이미 있는 클래스를 다시 만들지 않는다
- 의존 방향 `api → workflow-core·auth → common`. workflow-core가 api 기능이 필요하면 Provider 포트 + Stub(`workflow-core/CLAUDE.md`)
- 환경변수는 `.env` — `application-local.yml`에 쓰지 않는다
- 한 커밋 분량이 끝나면 멈추고 `✅ Step N/총 완료: <커밋 메시지>` + 바뀐 파일 + 다음 진행 여부로 보고한다
- 세부 규칙은 `.claude/rules/`(보안·git·Java 스타일·테스트·스키마)
- `verify-db-migration` 스킬은 Flyway 전제라 쓰지 않는다

# Gotchas
- dev push = dev Blue/Green 배포, main push = main 배포. 태그·릴리스 없음
- dev는 `ddl-auto: validate` 강제 — 엔티티를 바꾸면 `docs/schema` DDL을 머지 전에 dev PG에 적용(`rules/java/schema.md`)
- 테스트는 H2(PostgreSQL 모드)·`create-drop` — PG 전용 DDL·CHECK 제약 사고를 못 잡는다
- `org.springframework.boot` 플러그인은 `api`에만. `scanBasePackages = "com.ieum"` 빠지면 다른 모듈 빈이 안 잡힌다
- 레포 간 계약(카탈로그·agent 위임 헤더)은 워크스페이스 `../docs/cross-repo-contracts.md`
