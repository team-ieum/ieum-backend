package com.ieum.workflowcore.engine.executor;

/** 포맷터 변환 실패. 변환 함수는 이유만 담고, 접두사·입력 요약은 {@link Formatter#apply}가 붙인다. */
final class FormatterFailure extends RuntimeException {

    FormatterFailure(String reason) {
        super(reason);
    }
}
