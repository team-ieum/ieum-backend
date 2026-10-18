package com.ieum.workflowcore.trigger;

/** 트리거 노드 테스트 방식 */
public enum NodeTestMode {
    /** 러너로 바로 실행 */
    RUN,
    /** 토큰 주소로 수신 대기(WEBHOOK) */
    LISTEN,
    /** 최근 1건을 즉시 샘플로 조회(앱 트리거) */
    FETCH
}
