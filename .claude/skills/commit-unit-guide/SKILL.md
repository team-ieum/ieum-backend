---
name: commit-unit-guide
description: 구현 작업을 커밋 단위로 분리하여 단계별로 안내합니다. 각 단계 완료 후 반드시 멈추고 대기하며, git 명령은 실행하지 않습니다.
argument-hint: "[구현할 기능 설명 또는 Notion 이슈 ID]"
---

# 커밋 단위 구현 가이드

## 목적
구현 작업을 논리적 커밋 단위로 분리하고, 각 단계를 순차적으로 진행한다.

## 핵심 규칙

### 반드시 지킬 것
1. **각 커밋 단위 완료 후 반드시 멈추고 대기** — 다음 진행 여부를 사용자에게 확인
2. **파일 생성/수정만 수행** — git 명령 실행 금지
3. **환경변수는 .env 파일에 작성** — application-local.yml 사용 금지
4. 해당 모듈의 `CLAUDE.md`를 먼저 읽고 이미 구현된 클래스 목록 확인

### 각 단계 완료 시 출력 형식
```
✅ Step {N}/{총 단계} 완료: {커밋 메시지}

📁 생성/수정된 파일:
  + path/to/NewFile.java (생성)
  ~ path/to/ExistingFile.java (수정)

💡 git 명령어:
  git add .
  git commit -m "{type}: {메시지}"

다음 단계 진행할까요? (Step {N+1}: {다음 단계 설명})
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
5. **DB 마이그레이션** — Flyway 스크립트 (V{버전}__{설명}.sql)
6. **설정 파일** — application.yml, SecurityConfig 등
7. **테스트** — 단위/통합 테스트

### DB 마이그레이션 버전 결정
- 기존 마이그레이션 파일 확인: `api/src/main/resources/db/migration/`
- 다음 버전 번호 사용 (V1 → V2 → V3 ...)
- 파일명: `V{버전}__{Snake_Case_설명}.sql` (언더바 2개)

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
