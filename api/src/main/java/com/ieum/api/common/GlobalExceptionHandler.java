package com.ieum.api.common;

import com.ieum.common.dto.ApiResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.validation.WorkflowValidationException;
import com.ieum.workflowcore.engine.validation.WorkflowValidationResult;
import com.ieum.workflowcore.service.TestSampleMissingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[{}] {}", code.name(), e.getMessage(), e);
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(TestSampleMissingException.class)
    public ResponseEntity<ApiResponse<Map<String, List<String>>>> handleTestSampleMissing(
            TestSampleMissingException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[{}] missingNodeIds={}", code.name(), e.getMissingNodeIds());
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage(),
                        Map.of("missingNodeIds", e.getMissingNodeIds())));
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, List<WorkflowValidationResult.ValidationError>>>> handleWorkflowValidation(
            WorkflowValidationException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage(), Map.of("errors", e.getErrors())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("[INVALID_INPUT] {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message));
    }

    // 요청 본문을 읽지 못한 경우 — JSON 문법 오류, 그리고 허용되지 않은 enum 값처럼 타입 변환에
    // 실패한 경우가 여기로 온다(예: 워크플로우 생성 요청의 triggerType에 없는 값). 핸들러가 없으면
    // 아래 Exception 핸들러가 잡아 500이 나가는데, 원인은 서버가 아니라 요청 쪽이다.
    // 노드 type은 이 경로가 아니다 — READ_UNKNOWN_ENUM_VALUES_AS_NULL이 붙어 역직렬화가 성공하고
    // null이 된 값을 @NotNull이 잡으므로 handleMethodArgumentNotValid로 간다(둘 다 400).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        // 예외 메시지에는 내부 클래스명과 필드 경로가 담기므로 응답에 그대로 싣지 않는다.
        log.warn("[INVALID_INPUT] 요청 본문 파싱 실패: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "잘못된 파라미터: " + e.getName();
        log.warn("[INVALID_INPUT] {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message));
    }

    // @Validated가 붙은 컨트롤러의 @RequestParam @Min/@Max 등 제약 위반 시 발생한다.
    // @Validated는 Spring MVC 내장 파라미터 검증(HandlerMethodValidationException)을 끄고
    // AOP MethodValidationInterceptor의 ConstraintViolationException만 던지므로 둘 다 받아둔다 —
    // 나중에 @Validated를 떼서 내장 경로로 돌아가도 400이 유지된다.
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(Exception e) {
        log.warn("[INVALID_INPUT] {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "잘못된 요청 파라미터입니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request) {
        // 없는 경로(404)·지원하지 않는 메서드(405) 같은 Spring MVC 요청 오류는 ErrorResponse가 상태를 들고 온다.
        // 여기서 500으로 덮으면 클라이언트 실수가 서버 오류로 보인다.
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            ErrorCode code = er.getStatusCode().value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_INPUT;
            String uri = loggableUri(request);
            // NoResourceFoundException 등은 메시지에 경로를 담는다 — 토큰 경로면 메시지 대신 예외 이름만 남긴다
            String detail = isHookPath(request) ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[{}] {} {} — {}", code.name(), request.getMethod(), uri, detail);
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders()).body(ApiResponse.error(code));
        }
        log.error("[UNHANDLED] {} {}", request.getMethod(), loggableUri(request), e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    private static final String HOOK_PATH_PREFIX = "/api/v1/hooks/";

    /** 웹훅 토큰 주소는 경로 자체가 비밀이라 토큰 구간을 가린다(IEUM-BE-91). */
    static String loggableUri(HttpServletRequest request) {
        return isHookPath(request) ? HOOK_PATH_PREFIX + "***" : request.getRequestURI();
    }

    // 디코드·정규화된 servletPath+pathInfo로 본다(Security와 같은 기준) — getRequestURI()면 %68ooks 같은 인코딩 경로가 빠져나간다
    private static boolean isHookPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return (request.getServletPath() + (pathInfo == null ? "" : pathInfo)).startsWith(HOOK_PATH_PREFIX);
    }
}
