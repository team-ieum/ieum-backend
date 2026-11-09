package com.ieum.workflowcore.engine.executor;

/** 기다리기 계산·큐 예약 실패. 메시지는 사용자에게 보이는 문구 그대로다. */
public final class DelayFailure extends RuntimeException {

    public DelayFailure(String message) {
        super(message);
    }
}
