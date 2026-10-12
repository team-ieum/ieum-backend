package com.ieum.workflowcore.engine.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * agent {@code POST /v1/workflows/validate} 응답이자 BE 검증 엔드포인트의 {@code data}.
 *
 * <p>차단 기준은 {@code valid}가 아니라 {@link #hasErrors()}다 — agent가 {@code valid:false}만 주고
 * 목록을 비워 보내면 막을 근거(어느 칸을 고칠지)가 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowValidationResult(boolean valid, List<ValidationError> errors) {

    public WorkflowValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * 에러 하나. {@code field}는 카탈로그 칸의 path(예: {@code config.channel}), 그래프·워크플로우 전체 에러면
     * {@code nodeId}·{@code field}가 null. {@code message}는 사용자에게 바로 보일 한국어 문장.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidationError(String nodeId, String field, String code, String message) {}
}
