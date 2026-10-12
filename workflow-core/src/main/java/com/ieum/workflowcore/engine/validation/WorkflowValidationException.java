package com.ieum.workflowcore.engine.validation;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import lombok.Getter;

/**
 * 정적 검증 에러가 있어 실행하지 않는다. 수동 실행은 400 + {@code data.errors}로, 런타임은 실행 실패 사유로 쓴다.
 * 메시지는 {@code "검증 실패 N건: <첫 message>"} — 자동 실행의 {@code workflow_runs.error_message}·실패 알림에 그대로 실린다.
 */
@Getter
public class WorkflowValidationException extends CustomException {

    private final List<WorkflowValidationResult.ValidationError> errors;

    /** @param errors 비어 있지 않은 목록({@link WorkflowValidationResult#hasErrors()}일 때만 만든다) */
    public WorkflowValidationException(List<WorkflowValidationResult.ValidationError> errors) {
        super(ErrorCode.WORKFLOW_VALIDATION_FAILED,
            "검증 실패 " + errors.size() + "건: " + errors.get(0).message());
        this.errors = List.copyOf(errors);
    }
}
