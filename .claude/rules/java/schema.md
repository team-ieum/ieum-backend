---
paths:
  - "**/src/main/java/**/domain/**"
  - "common/src/main/java/**/entity/**"
  - "docs/schema/**"
---
# 엔티티·스키마
- 엔티티: `BaseEntity` 상속, `@NoArgsConstructor(access = PROTECTED)`, 연관관계 `FetchType.LAZY`, `EnumType.STRING`(ORDINAL 금지), JSONB는 `columnDefinition = "jsonb"`
- Flyway가 없다. 스키마를 바꾸면 `docs/schema/V<다음 번호>__<설명>.sql`에 DDL을 남긴다
- dev는 `validate` 강제 — 머지 전에 dev PG에 DDL을 적용하지 않으면 후보 BE가 `Schema-validation: missing column`으로 기동 실패해 배포가 멈춘다(IEUM-BE-113)
- 옛 BE도 통과하는 DDL(nullable 컬럼 추가, CHECK 값 추가)만 먼저 적용해도 안전하다. 그 외는 PR `## 머지 순서`에 적는다
- enum 값을 추가하면 그 컬럼의 PG CHECK 제약도 DDL로 넓힌다(V10·V14·V17)
- 기존 행이 있는 테이블에 `nullable = false` 컬럼 → DDL에 `DEFAULT` + 엔티티에 `@ColumnDefault`. 로컬 `update`는 DDL 실패를 경고로만 남기고 컬럼 없이 뜬다
