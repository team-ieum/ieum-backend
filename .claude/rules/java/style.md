---
paths:
  - "**/src/main/java/**"
---
# Java 스타일
- 예외는 `CustomException(ErrorCode)` — RuntimeException 직접 throw 금지. 새 ErrorCode는 `common/CLAUDE.md` 규칙대로. 핸들러는 `api/.../common/GlobalExceptionHandler` 하나
- 응답은 `ApiResponse<T>` `{ success, data, message }`. 토큰·키는 응답 DTO에 넣지 않는다
- `@Tag`·`@Operation`은 `{Domain}ControllerDocs` 인터페이스에 두고 컨트롤러가 구현한다
- Service는 클래스에 `@Transactional(readOnly = true)`, 쓰기 메서드만 `@Transactional`
- 주입은 `@RequiredArgsConstructor` 생성자 주입. 로깅은 `@Slf4j`
- Repository: 저장·삭제·PK 조회는 `{Entity}Repository`(JpaRepository), 복잡한 조회는 `{Entity}QueryRepository`(`@Repository` + `JPAQueryFactory`)
- QueryDSL 7(openfeign)엔 `sum()`이 없다 — `sumLong()`·`sumAggregate()`, 전부 null이면 0 처리
- Response DTO는 `from(Entity)` 정적 팩토리
- 변수 참조 문법 `{{nodes.<uuid>.output.<field>}}`
- 구현 순서: 엔티티+Repository → Service → DTO → Controller → 설정 → 테스트
