---
name: verify-db-migration
description: Flyway DB 마이그레이션 규칙 준수 여부를 검증합니다. 의존성 존재, application.yml 설정, 파일 위치 및 명명 컨벤션, 버전 중복을 확인합니다.
---

# verify-db-migration

## 목적

Flyway 마이그레이션이 프로젝트 규칙에 맞게 설정되고 작성되었는지 검증합니다.

## 실행 시점

- 새 마이그레이션 파일을 추가했을 때
- `build.gradle` 또는 `application.yml` Flyway 설정을 변경했을 때
- PR 전 최종 검증 시

## Related Files

| File | Purpose |
|------|---------|
| `api/build.gradle` | Flyway 의존성 선언 |
| `api/src/main/resources/application.yml` | Flyway 설정 (enabled, locations) |
| `api/src/main/resources/db/migration/` | 마이그레이션 파일 디렉토리 |

## Workflow

### Check 1: Flyway 의존성 존재 확인

`api/build.gradle`에 `flyway-core`와 `flyway-database-postgresql`이 선언되어 있어야 합니다.

```bash
grep -n "flyway" api/build.gradle
```

**PASS:** `flyway-core`와 `flyway-database-postgresql` 모두 포함
**FAIL:** 둘 중 하나라도 없으면 Flyway가 실행되지 않음

### Check 2: application.yml Flyway 설정 확인

`flyway.enabled: true`이고 `locations`가 올바르게 설정되어 있어야 합니다.

```bash
grep -A3 "flyway:" api/src/main/resources/application.yml
```

**PASS:** `enabled: true`, `locations: classpath:db/migration` 포함
**FAIL:** `enabled: false`이거나 locations 누락

### Check 3: 마이그레이션 파일 위치 확인

모든 마이그레이션 파일은 `api/src/main/resources/db/migration/`에 위치해야 합니다.

```bash
find . -name "V*.sql" | grep -v build | grep -v test
```

**PASS:** 모든 `.sql` 파일이 `api/src/main/resources/db/migration/` 하위에 위치
**FAIL:** 다른 경로에 마이그레이션 파일이 존재

### Check 4: 파일명 컨벤션 확인

파일명은 `V{버전}__{설명}.sql` 형식이어야 합니다 (언더바 **2개**).

```bash
ls api/src/main/resources/db/migration/ | grep -v "^$"
```

**PASS:** 모든 파일이 `V숫자__설명.sql` 패턴 (예: `V1__create_users_table.sql`)
**FAIL:** 언더바가 1개이거나 `V` 접두사 없음 — Flyway가 파일을 인식하지 못함

### Check 5: 버전 중복 확인

동일한 버전 번호를 가진 마이그레이션 파일이 없어야 합니다.

```bash
ls api/src/main/resources/db/migration/ | grep -oE "^V[0-9]+" | sort | uniq -d
```

**PASS:** 결과가 없음 (중복 버전 없음)
**FAIL:** 중복 버전 번호 출력 — Flyway 시작 시 오류 발생

## 예외사항

다음은 **위반이 아닙니다**:

1. **`.gitkeep` 파일** — 빈 디렉토리 유지용이며 마이그레이션 파일 아님
2. **`R` 접두사 파일** — `R__{description}.sql`은 Flyway Repeatable 마이그레이션으로 허용
3. **테스트용 마이그레이션** — `src/test` 하위의 마이그레이션 파일은 검사 제외
4. **`build/` 하위 복사본** — Gradle 빌드 결과물이며 원본이 아님
