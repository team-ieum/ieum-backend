# PR
- base는 `dev`
- 제목 `[type] 한국어 설명 (IEUM-BE-n)` — type: `feat`·`fix`·`refactor`·`chore`·`test`, 인프라는 `[Infra]`
- 본문 `## 무엇` → 필요할 때 `## DDL — 머지 전 dev 수동 적용`·`## 머지 순서`(배포·롤백 순서)·`## FE 영향` → `## 테스트`(`./gradlew build` 건수·실패 수) → 마지막 줄 이슈 ID + Notion 링크
- 머지는 Create a merge commit (squash 아님) — 작업 브랜치의 커밋 단위를 dev에 남긴다
- PR CI(`ci.yml`)는 build + test만. PG·Redis 실물 테스트는 dev push 뒤 배포 잡에서 돈다
