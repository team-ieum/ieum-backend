---
name: verify-db-migration
description: Flyway DB 마이그레이션 파일의 규칙 준수 여부를 검증합니다. 파일명 형식, UUID 기본값, TIMESTAMP 컬럼, 인덱스 네이밍, Enum 컬럼 타입을 확인합니다.
---

# verify-db-migration

## 목적

Flyway 마이그레이션 SQL 파일이 프로젝트 DB 스키마 규칙을 준수하는지 검증합니다.

## 실행 시점

- 새로운 마이그레이션 파일을 추가했을 때
- 기존 마이그레이션을 수정했을 때
- PR 전 최종 검증 시

## Related Files

| File | Purpose |
|------|---------|
| `api/src/main/resources/db/migration/` | Flyway 마이그레이션 파일 위치 |
| `api/src/main/resources/db/migration/V1__create_users_table.sql` | 마이그레이션 예시 |

## Workflow

### Check 1: 파일명 형식 확인

마이그레이션 파일명은 반드시 `V{숫자}__{설명}.sql` 형식이어야 합니다 (언더바 2개).

```bash
# 마이그레이션 디렉토리의 파일 목록
ls api/src/main/resources/db/migration/

# 잘못된 파일명 탐지 (V숫자__ 형식이 아닌 파일, .gitkeep 제외)
find api/src/main/resources/db/migration/ -name "*.sql" | \
  grep -Ev "V[0-9]+__[^/]+\.sql$"
```

**PASS:** 결과가 없음 (모든 .sql 파일이 올바른 형식)
**FAIL:** 1건 이상 — `V{N}__{설명}.sql` 형식으로 파일명 변경 필요

### Check 2: UUID PK에 gen_random_uuid() 사용 확인

UUID 타입의 PK는 반드시 `DEFAULT gen_random_uuid()`를 사용해야 합니다.

```bash
# UUID PRIMARY KEY가 있지만 gen_random_uuid()가 없는 파일 탐지
grep -rln "UUID PRIMARY KEY" api/src/main/resources/db/migration/ | \
  xargs grep -L "gen_random_uuid()"
```

**PASS:** 결과가 없음 (모든 UUID PK가 gen_random_uuid() 사용)
**FAIL:** 1건 이상 — `DEFAULT gen_random_uuid()` 추가 필요

### Check 3: created_at / updated_at TIMESTAMP 컬럼 확인

모든 테이블은 `created_at`, `updated_at` TIMESTAMP NOT NULL 컬럼을 포함해야 합니다.

```bash
# CREATE TABLE이 있는 파일 목록
grep -rln "CREATE TABLE" api/src/main/resources/db/migration/

# created_at이 없는 마이그레이션 파일 탐지
grep -rln "CREATE TABLE" api/src/main/resources/db/migration/ | \
  xargs grep -L "created_at"

# updated_at이 없는 마이그레이션 파일 탐지
grep -rln "CREATE TABLE" api/src/main/resources/db/migration/ | \
  xargs grep -L "updated_at"
```

**PASS:** 모든 CREATE TABLE 파일에 created_at, updated_at 존재
**FAIL:** 누락된 파일 — TIMESTAMP NOT NULL DEFAULT now() 컬럼 추가 필요

### Check 4: 인덱스 네이밍 규칙 확인

인덱스명은 반드시 `idx_{테이블명}_{컬럼명}` 형식이어야 합니다.

```bash
# CREATE INDEX 구문 확인
grep -rn "CREATE INDEX" api/src/main/resources/db/migration/

# idx_ 접두사가 없는 인덱스 탐지
grep -rn "CREATE INDEX" api/src/main/resources/db/migration/ | grep -v "idx_"
```

**PASS:** 모든 인덱스명이 `idx_`로 시작
**FAIL:** 1건 이상 — `idx_{테이블}_{컬럼}` 형식으로 수정

### Check 5: Enum 컬럼이 VARCHAR 타입인지 확인

Enum 값을 저장하는 컬럼은 INT가 아닌 VARCHAR를 사용해야 합니다.

```bash
# provider, status, type 등 Enum성 컬럼이 INT로 선언된 경우 탐지
grep -rn "provider\|status\|type\|role" api/src/main/resources/db/migration/ | \
  grep -i "INT\b" | grep -v "VARCHAR" | grep -v "--"
```

**PASS:** 결과가 없음 (Enum 컬럼이 VARCHAR로 선언됨)
**FAIL:** INT로 선언된 Enum 컬럼 — `VARCHAR(N)` 으로 변경 필요

## 예외사항

다음은 **위반이 아닙니다**:

1. **`.gitkeep` 파일** — 빈 디렉토리 유지용 파일은 검사 제외
2. **ALTER TABLE 마이그레이션** — 테이블 수정 파일은 Check 3 (Auditing 컬럼) 제외
3. **연결 테이블 (Junction Table)** — `created_at`/`updated_at` 없어도 허용 (비즈니스 의미 없는 경우)
4. **인덱스 없는 테이블** — 인덱스 추가가 불필요한 소규모 테이블은 Check 4 제외
