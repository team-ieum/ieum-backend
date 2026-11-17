---
name: commit-unit-guide
description: 구현 작업을 커밋 단위로 분리하여 단계별로 안내합니다. 각 단계 완료 후 반드시 멈추고, 사용자가 승인하면 그 단계를 커밋합니다.
argument-hint: "[구현할 기능 설명 또는 Notion 이슈 ID]"
---

# 커밋 단위 구현 가이드

## 목적
구현 작업을 논리적 커밋 단위로 분리하고, 각 단계를 순차적으로 진행한다.

## 핵심 규칙

### 반드시 지킬 것
1. **각 커밋 단위 완료 후 반드시 멈추고 대기** — 다음 진행 여부를 사용자에게 확인
2. **커밋은 사용자 승인 후에만** — 그 단계에서 바꾼 파일만 `git add <파일>`로 올리고 커밋한다(`git add .` 금지). push는 하지 않는다
3. **환경변수는 .env 파일에 작성** — application-local.yml 사용 금지
4. 해당 모듈의 `CLAUDE.md`를 먼저 읽고 이미 구현된 클래스 목록 확인

### 각 단계 완료 시 출력 형식
```
✅ Step {N}/{총 단계} 완료: {커밋 메시지}

📁 생성/수정된 파일:
  + path/to/NewFile.java (생성)
  ~ path/to/ExistingFile.java (수정)

이 단계를 커밋하고 다음 단계로 갈까요? (Step {N+1}: {다음 단계 설명})
```

### 건너뛸 것 (이미 구현됨 — common 모듈)
- ErrorCode, CustomException, SuccessCode
- ApiResponse, PageResponse
- BaseEntity
- JpaAuditingConfig, QueryDslConfig
- AesEncryptor

### 구현 순서 원칙
1. **엔티티 + Repository** — 도메인 레이어 (JPA 엔티티, Repository 인터페이스/구현)
2. **Service** — 비즈니스 로직 (@Transactional(readOnly=true) 기본)
3. **DTO** — Request/Response (from() 정적 팩토리)
4. **Controller** — API 레이어 (Swagger 문서화, ControllerDocs 인터페이스)
5. **스키마 DDL** — 엔티티를 바꿨을 때만. `docs/schema/V{버전}__{설명}.sql` (Flyway 없음)
6. **설정 파일** — application.yml, SecurityConfig 등
7. **테스트** — 단위/통합 테스트

### 스키마 DDL
- `docs/schema/`의 마지막 번호 다음을 쓴다. 파일명 `V{버전}__{snake_case_설명}.sql` (언더바 2개)
- dev는 `ddl-auto: validate` 강제 — DDL을 머지 전에 dev PG에 수동 적용해야 배포가 안 멈춘다. 단계 보고와 PR 본문 `## DDL — 머지 전 dev 수동 적용`에 적는다
- 세부 규칙은 `.claude/rules/java/schema.md`

## 커밋 메시지 컨벤션
- `feat:` 새 기능
- `fix:` 버그 수정
- `refactor:` 리팩토링 (기능 변경 없음)
- `docs:` 문서
- `test:` 테스트
- `chore:` 설정/빌드

## 코드 품질 체크 (각 단계 완료 전)
- [ ] 패키지 구조가 `com.ieum.<모듈명>` 규칙을 따르는가
- [ ] 엔티티에 `@NoArgsConstructor(access = PROTECTED)` 적용했는가
- [ ] FetchType.LAZY 기본인가
- [ ] EnumType.STRING 사용했는가 (ORDINAL 금지)
- [ ] 예외는 CustomException + ErrorCode 사용했는가
- [ ] 보안 민감 데이터가 응답/로그에 노출되지 않는가
