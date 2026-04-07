package com.ieum.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

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
    INVALID_CREDENTIALS(HttpStatus.BAD_REQUEST, "이메일 또는 비밀번호가 일치하지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "이미 등록된 이메일입니다."),
    SOCIAL_LOGIN_EMAIL_CONFLICT(HttpStatus.CONFLICT, "동일한 이메일로 가입된 계정이 있습니다. 이메일/비밀번호로 로그인해주세요."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),

    // 크레덴셜 (BYOK)
    INVALID_API_KEY(HttpStatus.BAD_REQUEST, "프로바이더 API 키 검증에 실패했습니다."),
    INVALID_API_KEY_FORMAT(HttpStatus.BAD_REQUEST, "API 키 형식이 올바르지 않습니다. 프로바이더별 키 형식을 확인해주세요."),
    CREDENTIAL_DUPLICATE_NAME(HttpStatus.CONFLICT, "같은 프로바이더에 동일한 이름의 크레덴셜이 이미 존재합니다."),
    CREDENTIAL_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "크레덴셜 등록 한도를 초과했습니다."),
    CREDENTIAL_NO_BILLING(HttpStatus.PAYMENT_REQUIRED, "프로바이더 계정에 결제 수단이 등록되어 있지 않습니다. 프로바이더 대시보드에서 결제 설정을 확인해주세요."),
    CREDENTIAL_EXPIRED(HttpStatus.UNAUTHORIZED, "OAuth 토큰이 만료되었습니다."),
    CREDENTIAL_IN_USE(HttpStatus.CONFLICT, "워크플로우에서 사용 중인 크레덴셜입니다."),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 프로바이더 서버에 일시적인 장애가 발생했습니다. 잠시 후 다시 시도해주세요."),
    CREDENTIAL_VALIDATION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 프로바이더 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."),
    CREDENTIAL_VALIDATION_NETWORK_ERROR(HttpStatus.BAD_GATEWAY, "AI 프로바이더와의 연결에 실패했습니다. 네트워크 상태를 확인해주세요."),
    CREDENTIAL_DECRYPT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "크레덴셜 복호화에 실패했습니다. 데이터가 손상되었거나 암호화 키가 변경되었을 수 있습니다."),

    // 프롬프트 템플릿
    INVALID_PROMPT_TEMPLATE(HttpStatus.BAD_REQUEST, "프롬프트 템플릿 형식이 올바르지 않습니다."),
    UNRESOLVED_VARIABLE(HttpStatus.BAD_REQUEST, "필수 변수가 치환되지 않았습니다."),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "프롬프트 템플릿을 찾을 수 없습니다."),
    TEMPLATE_IN_USE(HttpStatus.CONFLICT, "AI 노드에서 참조 중인 템플릿입니다."),

    // 워크플로우
    WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "워크플로우를 찾을 수 없습니다."),
    INVALID_WORKFLOW(HttpStatus.BAD_REQUEST, "워크플로우 구성이 올바르지 않습니다."),
    EXECUTION_NOT_FOUND(HttpStatus.NOT_FOUND, "실행 기록을 찾을 수 없습니다."),

    // AI 에이전트
    INVALID_MODEL(HttpStatus.BAD_REQUEST, "프로바이더에서 지원하지 않는 모델입니다."),
    INVALID_TOOL_NAME(HttpStatus.BAD_REQUEST, "존재하지 않는 도구입니다."),
    BUDGET_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "토큰 예산을 초과했습니다."),
    MAX_ITERATIONS_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "최대 LLM 호출 횟수에 도달했습니다."),
    MAX_TOOL_CALLS_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "최대 도구 호출 횟수에 도달했습니다."),
    CONTEXT_OVERFLOW(HttpStatus.UNPROCESSABLE_ENTITY, "대화 히스토리가 컨텍스트 윈도우를 초과했습니다."),
    PROVIDER_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "LLM 프로바이더 요청 한도를 초과했습니다."),
    PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "LLM 프로바이더 응답 오류가 발생했습니다."),
    PROVIDER_RETRIES_EXHAUSTED(HttpStatus.BAD_GATEWAY, "LLM 프로바이더 재시도가 모두 실패했습니다."),
    TOOL_EXECUTION_FAILED(HttpStatus.BAD_GATEWAY, "도구 실행에 실패했습니다."),
    PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "LLM 프로바이더 응답 시간이 초과되었습니다."),

    // 연동 계정
    ACCOUNT_NOT_CONNECTED(HttpStatus.BAD_REQUEST, "연동된 계정이 없습니다."),
    TOKEN_REFRESH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "토큰 갱신에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
