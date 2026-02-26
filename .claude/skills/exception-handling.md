# Skill: 예외 처리 패턴

## 목적
IEUM 프로젝트 전반에 걸쳐 일관된 예외 처리 구조를 유지한다.
공통 응답 포맷 `{ success, data, message }`에 맞춰 에러를 반환한다.

## 공통 예외 클래스 (common 모듈)

```java
// com.ieum.common.exception
@Getter
public class IeumException extends RuntimeException {
    private final ErrorCode errorCode;

    public IeumException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),

    // 연동 계정
    ACCOUNT_NOT_CONNECTED(HttpStatus.BAD_REQUEST, "연동된 계정이 없습니다."),
    TOKEN_REFRESH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "토큰 갱신에 실패했습니다."),

    // 워크플로우
    WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "워크플로우를 찾을 수 없습니다."),
    WORKFLOW_ALREADY_RUNNING(HttpStatus.CONFLICT, "워크플로우가 이미 실행 중입니다."),

    // AI
    AI_CREDENTIAL_INVALID(HttpStatus.BAD_REQUEST, "AI 자격증명이 유효하지 않습니다."),
    AI_API_CALL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AI API 호출에 실패했습니다."),
    ;

    private final HttpStatus status;
    private final String message;
}
```

## 공통 에러 응답 형식 (API 스펙에 맞춤)

```java
// 실패 응답은 ApiResponse 래퍼의 error 형태로 반환
// { "success": false, "data": null, "message": "에러 메시지" }
```

## Global Exception Handler (api 모듈)

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IeumException.class)
    public ResponseEntity<ApiResponse<Void>> handleIeumException(IeumException e) {
        log.warn("IeumException: {}", e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
```

## 사용 예시
```java
// Service에서 사용
public Workflow findById(UUID id) {
    return repository.findById(id)
            .orElseThrow(() -> new IeumException(ErrorCode.WORKFLOW_NOT_FOUND));
}
```

## HTTP 상태 코드 가이드 (API 스펙)
| 코드 | 설명 | 사용 시점 |
|------|------|----------|
| 200 | 성공 | 조회, 수정 성공 |
| 201 | 생성 성공 | POST로 리소스 생성 |
| 400 | 잘못된 요청 | 입력 검증 실패 |
| 401 | 인증 실패 | JWT 토큰 없음/만료 |
| 403 | 권한 없음 | 리소스 접근 권한 부족 |
| 404 | 리소스 없음 | 존재하지 않는 ID 조회 |
| 409 | 충돌 | 이미 실행 중인 워크플로우 등 |
| 500 | 서버 에러 | 예상치 못한 오류 |
