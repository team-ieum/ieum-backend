---
name: verify-jpa-entity
description: JPA 엔티티 및 Repository 규칙 준수 여부를 검증합니다. BaseEntity 상속, EnumType.STRING, 생성자 접근 제어, Repository 커맨드/쿼리 분리, QueryDSL 사용 규칙을 확인합니다.
---

# verify-jpa-entity

## 목적

JPA 엔티티와 Repository가 프로젝트 규칙에 맞게 작성되었는지 검증합니다.

## 실행 시점

- 새로운 엔티티 또는 Repository를 추가했을 때
- 엔티티 필드를 수정했을 때
- PR 전 최종 검증 시

## Related Files

| File | Purpose |
|------|---------|
| `common/src/main/java/com/ieum/common/entity/BaseEntity.java` | 공통 Auditing 기반 클래스 |
| `common/src/main/java/com/ieum/common/config/JpaAuditingConfig.java` | JPA Auditing 활성화 설정 |
| `common/src/main/java/com/ieum/common/config/QueryDslConfig.java` | JPAQueryFactory Bean 등록 |
| `auth/src/main/java/com/ieum/auth/domain/User.java` | 엔티티 예시 |
| `auth/src/main/java/com/ieum/auth/domain/AuthProvider.java` | Enum 예시 |
| `auth/src/main/java/com/ieum/auth/repository/UserRepository.java` | 커맨드 Repository 예시 |
| `auth/src/main/java/com/ieum/auth/repository/UserRepositoryCustom.java` | 쿼리 Repository 인터페이스 예시 |
| `auth/src/main/java/com/ieum/auth/repository/UserRepositoryImpl.java` | 쿼리 Repository 구현체 예시 |
| `auth/src/main/java/com/ieum/auth/domain/RefreshToken.java` | Redis 도메인 예시 (`@RedisHash`) |
| `auth/src/main/java/com/ieum/auth/repository/RefreshTokenRepository.java` | Redis CrudRepository 예시 |
| `ai/src/main/java/com/ieum/ai/credential/domain/Credential.java` | 크레덴셜 엔티티 |
| `ai/src/main/java/com/ieum/ai/credential/domain/AiProvider.java` | AI 프로바이더 Enum |
| `ai/src/main/java/com/ieum/ai/credential/domain/CredentialType.java` | 크레덴셜 타입 Enum |
| `ai/src/main/java/com/ieum/ai/credential/repository/CredentialRepository.java` | 크레덴셜 커맨드 Repository |
| `ai/src/main/java/com/ieum/ai/credential/repository/CredentialQueryRepository.java` | 크레덴셜 쿼리 Repository (QueryDSL 독립 클래스 패턴) |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/Workflow.java` | 워크플로우 엔티티 |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/WorkflowVersion.java` | 워크플로우 버전 엔티티 |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/WorkflowExecution.java` | 워크플로우 실행 엔티티 (`workflow_runs` 테이블) |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/WorkflowExecutionLog.java` | 노드 실행 로그 엔티티 (`node_runs` 테이블) |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/enums/ExecutionStatus.java` | 실행 상태 Enum |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/enums/TriggerType.java` | 트리거 타입 Enum |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/enums/NodeType.java` | 노드 타입 Enum |
| `workflow-core/src/main/java/com/ieum/workflowcore/domain/enums/ExecutionLogStatus.java` | 노드 실행 결과 Enum |
| `workflow-core/src/main/java/com/ieum/workflowcore/repository/WorkflowRepository.java` | 워크플로우 커맨드 Repository |
| `workflow-core/src/main/java/com/ieum/workflowcore/repository/WorkflowVersionRepository.java` | 워크플로우 버전 Repository (`@Query` JPQL 패턴) |
| `workflow-core/src/main/java/com/ieum/workflowcore/repository/WorkflowExecutionRepository.java` | 실행 이력 Repository |
| `workflow-core/src/main/java/com/ieum/workflowcore/repository/WorkflowExecutionLogRepository.java` | 노드 실행 로그 Repository |

## Workflow

### Check 1: @Entity 클래스의 BaseEntity 상속 확인

모든 `@Entity` 클래스는 `BaseEntity`를 상속해야 합니다.

```bash
# @Entity 클래스 목록 (@Entity 어노테이션 정확히 탐지, EntityListeners 등 제외)
grep -rln "^\s*@Entity\b" --include="*.java" . | grep -v test | grep -v build

# BaseEntity를 상속하지 않은 @Entity 탐지
grep -rln "^\s*@Entity\b" --include="*.java" . | grep -v test | grep -v build | \
  xargs grep -L "extends BaseEntity"
```

**PASS:** 결과가 없음 (모든 @Entity가 BaseEntity 상속)
**FAIL:** 1건 이상 — `extends BaseEntity` 추가 필요

### Check 2: @NoArgsConstructor(access = AccessLevel.PROTECTED) 확인

엔티티는 기본 생성자를 `PROTECTED`로 제한해야 합니다.

```bash
# @Entity가 있지만 @NoArgsConstructor(access = AccessLevel.PROTECTED)가 없는 파일 탐지
grep -rln "^\s*@Entity\b" --include="*.java" . | grep -v test | grep -v build | \
  xargs grep -L "AccessLevel.PROTECTED"
```

**PASS:** 결과가 없음
**FAIL:** 1건 이상 — `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 추가 필요

### Check 3: @Enumerated에 EnumType.STRING 사용 확인

`@Enumerated` 어노테이션은 반드시 `EnumType.STRING`이어야 합니다.

```bash
# EnumType.ORDINAL 사용 탐지
grep -rn "EnumType\.ORDINAL" --include="*.java" . | grep -v test | grep -v build

# @Enumerated만 있고 EnumType 지정 없는 경우 탐지 (기본값 ORDINAL)
grep -rn "@Enumerated$\|@Enumerated " --include="*.java" . | grep -v test | grep -v build | \
  grep -v "EnumType"
```

**PASS:** 두 명령어 모두 결과 없음
**FAIL:** `EnumType.ORDINAL` 또는 EnumType 미지정 → `@Enumerated(EnumType.STRING)`으로 교체

### Check 4: camelCase 필드의 @Column(name) snake_case 명시 확인

camelCase 필드명(2단어 이상)은 반드시 `@Column(name = "snake_case")`를 명시해야 합니다.

```bash
# camelCase 필드에 @Column이 있지만 name 속성이 없는 경우 탐지
grep -rn "@Column" --include="*.java" . | grep -v test | grep -v build | \
  grep -v 'name\s*=' | grep -v "//.*@Column"
```

**PASS:** camelCase 필드에는 모두 `name` 속성 존재
**FAIL:** `name` 없는 @Column이 camelCase 필드에 사용됨 — `@Column(name = "snake_case_name")` 추가

### Check 5: Repository 커맨드/쿼리 분리 확인

도메인마다 `{Entity}Repository` (JpaRepository)와 `{Entity}RepositoryCustom` + `{Entity}RepositoryImpl` 쌍이 존재해야 합니다.

```bash
# JpaRepository를 상속하는 Repository 인터페이스 목록
grep -rln "JpaRepository" --include="*.java" . | grep -v test | grep -v build

# RepositoryCustom 인터페이스 목록
grep -rln "RepositoryCustom" --include="*.java" . | grep -v test | grep -v build

# RepositoryImpl 구현체 목록
grep -rln "RepositoryImpl" --include="*.java" . | grep -v test | grep -v build
```

**PASS:** JpaRepository 상속 인터페이스 수 == RepositoryCustom 수 == RepositoryImpl 수
**FAIL:** 짝이 맞지 않음 — Custom 인터페이스 또는 Impl 구현체 누락

### Check 6: RepositoryImpl에 JPAQueryFactory 사용 확인

`*RepositoryImpl` 클래스는 반드시 `JPAQueryFactory`를 주입받아야 합니다.

```bash
# RepositoryImpl 파일 중 JPAQueryFactory를 사용하지 않는 경우 탐지
grep -rln "RepositoryImpl" --include="*.java" . | grep -v test | grep -v build | \
  xargs grep -L "JPAQueryFactory"
```

**PASS:** 결과가 없음 (모든 Impl이 JPAQueryFactory 사용)
**FAIL:** 1건 이상 — `JPAQueryFactory` 주입 필요

### Check 7: FetchType.EAGER 사용 금지

연관관계는 반드시 `FetchType.LAZY`를 사용해야 합니다.

```bash
grep -rn "FetchType\.EAGER" --include="*.java" . | grep -v test | grep -v build
```

**PASS:** 결과가 없음 (0건)
**FAIL:** 1건 이상 — `FetchType.LAZY`로 교체 필요

### Check 8: @Modifying bulk DELETE에 clearAutomatically = true 확인

`@Modifying @Query("DELETE ...")` 형태의 bulk DELETE는 반드시 `clearAutomatically = true`를 설정해야 합니다.
미설정 시 삭제된 엔티티가 1차 캐시에 남아 다음 flush 시 `TransientObjectException`이 발생합니다.

```bash
# @Modifying이 있는 파일 확인
grep -rn "@Modifying" --include="*.java" . | grep -v test | grep -v build

# clearAutomatically = true 없이 @Modifying만 있는 경우 탐지
grep -rln "@Modifying" --include="*.java" . | grep -v test | grep -v build | \
  xargs grep -l "DELETE" | xargs grep -L "clearAutomatically"
```

**PASS:** bulk DELETE가 있는 `@Modifying`에 모두 `clearAutomatically = true` 포함
**FAIL:** 1건 이상 — `@Modifying(clearAutomatically = true)`로 변경 필요

## 예외사항

다음은 **위반이 아닙니다**:

1. **BaseEntity 자체** — `BaseEntity`는 `@MappedSuperclass`이며 자기 자신을 상속하지 않음
2. **테스트 코드** — `src/test` 하위 파일은 검사 제외
3. **단일 단어 필드** — `email`, `name`, `id` 등 단일 단어 필드는 `@Column(name)` 생략 가능
4. **빌드 생성 파일** — `build/` 하위 Q클래스는 검사 제외
5. **@AllArgsConstructor(access = AccessLevel.PRIVATE)** — 엔티티에서 PRIVATE은 Builder 패턴과 함께 허용
6. **@RedisHash 도메인** — `RefreshToken` 등 Redis 엔티티는 `@Entity`가 없으므로 Check 1, 2 제외. `BaseEntity` 상속 불필요
7. **CrudRepository (Redis용)** — `RefreshTokenRepository`처럼 `CrudRepository`를 상속하는 Redis Repository는 Check 5(JpaRepository 쌍 검사) 제외
8. **`{Entity}QueryRepository` 단독 클래스 패턴** — ai 모듈처럼 `RepositoryCustom`/`RepositoryImpl` 인터페이스 분리 없이 `@Repository` 클래스에 직접 JPAQueryFactory를 주입하는 `{Entity}QueryRepository` 방식은 CLAUDE.md 공식 패턴으로 Check 5 예외. Check 6(`JPAQueryFactory` 사용 여부)은 동일하게 적용
9. **단순 `@Query` JPQL 패턴** — `WorkflowVersionRepository`처럼 ORDER BY + LIMIT 등 단순 JPQL이 필요한 경우 JpaRepository 인터페이스에 `@Query`를 직접 선언하는 것은 허용. QueryDSL이 필요한 복잡한 동적 쿼리(다중 조건, 정렬 동적 처리 등)에만 `QueryRepository` 분리 필요
