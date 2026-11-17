---
paths:
  - "**/src/test/**"
---
# 테스트
- 메서드명은 영어, 설명은 `@DisplayName` 한글 — `.gemini/styleguide.md`의 한글 메서드명 규칙과 그 리뷰 지적은 따르지 않는다
- 단위 테스트는 `@ExtendWith(MockitoExtension.class)` + `@InjectMocks` 위주. `@SpringBootTest`는 빈 배선 확인용
- 파일은 `@TempDir`에 쓴다
- 테스트 DB는 H2(PostgreSQL 모드)+`create-drop`(`api/src/test/resources/application.yml`). 컨텍스트가 캐시돼 H2를 공유하므로 새 sweeper·스케줄러는 거기서 `sweep-initial-delay: P1D`로 끈다
- PG·Redis 실물이 필요한 테스트는 `@EnabledIfEnvironmentVariable(named = "IEUM_TEST_POSTGRES_URL", matches = ".+")`로 막는다
- agent와 같은 규칙을 지켜야 하는 정규화는 고정 예시 JSON을 `src/test/resources/<도메인>/`에 두고 대조한다(예: `forms/normalize_case1.json`)
