package com.ieum.workflowcore.engine.executor;

/** 사람 확인 게이트 설정·제출 값 검사 실패. 메시지는 사용자에게 보이는 문구 그대로다. */
public final class ApprovalFailure extends RuntimeException {

    public ApprovalFailure(String message) {
        super(message);
    }
}
