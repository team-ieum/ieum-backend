---
name: verify-api-response
description: API 응답 포맷 및 예외 처리 규칙 준수 여부를 검증합니다. Controller의 ApiResponse 사용, Service의 CustomException 사용, GlobalExceptionHandler 단일 존재 여부를 확인합니다.
---

# verify-api-response

## 목적

`ApiResponse`, `CustomException`, `ErrorCode`, `GlobalExceptionHandler`가 프로젝트 규칙에 맞게 사용되는지 검증합니다.

## 실행 시점

- Controller 또는 Service 클래스를 새로 추가했을 때
- 예외 처리 로직을 수정했을 때
- PR 전 최종 검증 시

## Related Files

| File | Purpose |
|------|---------|
| `common/src/main/java/com/ieum/common/dto/ApiResponse.java` | 공통 응답 래퍼 |
| `common/src/main/java/com/ieum/common/dto/PageResponse.java` | 커서 기반 페이지 응답 |
| `common/src/main/java/com/ieum/common/exception/ErrorCode.java` | 에러 코드 enum |
| `common/src/main/java/com/ieum/common/exception/SuccessCode.java` | 성공 코드 enum |
| `common/src/main/java/com/ieum/common/exception/CustomException.java` | 비즈니스 예외 클래스 |
| `api/src/main/java/com/ieum/api/common/GlobalExceptionHandler.java` | 전역 예외 핸들러 |

## Workflow

### Check 1: Controller — ApiResponse 반환 확인

모든 Controller 메서드는 `ApiResponse` 또는 `ResponseEntity<ApiResponse<...>>`를 반환해야 합니다.

```bash
# Controller 파일 목록 확인
find . -path "*/controller/*Controller.java" | grep -v test

# ResponseEntity나 ApiResponse 없이 다른 타입을 반환하는 메서드 탐지
grep -rn "public.*\(.*\)" --include="*Controller.java" . | grep -v "ApiResponse" | grep -v "ResponseEntity" | grep -v "//"
```

**PASS:** 모든 Controller 메서드가 `ApiResponse<T>` 또는 `ResponseEntity<ApiResponse<T>>`를 반환
**FAIL:** `String`, 도메인 엔티티, DTO를 직접 반환

### Check 2: Service — CustomException 사용 확인

Service 클래스는 비즈니스 예외를 던질 때 반드시 `CustomException`을 사용해야 합니다.

```bash
# RuntimeException, IllegalArgumentException 등을 직접 throw하는 코드 탐지
grep -rn "throw new RuntimeException\|throw new IllegalArgumentException\|throw new IllegalStateException" \
  --include="*.java" . | grep -v test | grep -v "GlobalExceptionHandler"
```

**PASS:** 해당 grep 결과가 없음 (0건)
**FAIL:** 1건 이상 — `throw new CustomException(ErrorCode.XXX)`로 교체 필요

### Check 3: GlobalExceptionHandler 단일 존재 확인

`@RestControllerAdvice`가 붙은 클래스는 `api` 모듈에 하나만 있어야 합니다.

```bash
grep -rn "@RestControllerAdvice" --include="*.java" . | grep -v test
```

**PASS:** 결과가 정확히 1건이며 `api/src/main/java/com/ieum/api/common/GlobalExceptionHandler.java`
**FAIL:** 0건(누락) 또는 2건 이상(중복)

### Check 4: ErrorCode 없는 에러 응답 직접 문자열 사용 금지

`ApiResponse.error()`를 호출할 때 ErrorCode 없이 문자열만 사용하는 경우를 탐지합니다.
(단, `GlobalExceptionHandler` 내부는 허용)

```bash
grep -rn 'ApiResponse\.error("[^"]*")' --include="*.java" . | grep -v test | grep -v GlobalExceptionHandler
```

**PASS:** 결과가 없음 (0건)
**FAIL:** 1건 이상 — `ErrorCode`에 에러 코드를 추가하고 `ApiResponse.error(ErrorCode.XXX)` 형태로 사용

### Check 5: CustomException 생성 시 ErrorCode 사용 확인

`CustomException`은 반드시 `ErrorCode`와 함께 생성해야 합니다.

```bash
grep -rn "new CustomException(" --include="*.java" . | grep -v test
```

**PASS:** 모든 결과에 `ErrorCode.` 포함
**FAIL:** `ErrorCode` 없이 생성된 경우

## 예외사항

다음은 **위반이 아닙니다**:

1. **GlobalExceptionHandler 내부** — `ApiResponse.error(String)` 사용 허용 (fallback 핸들러)
2. **테스트 코드** — `src/test` 하위 파일은 검사 제외
3. **Controller가 아직 없는 경우** — Check 1은 Controller 파일이 0개면 PASS 처리
4. **`@RestController` 없는 클래스** — Controller 어노테이션이 없는 클래스는 Check 1 제외
5. **인터페이스/추상 클래스** — 구현체가 아닌 선언부는 Check 2 제외
