package com.ieum.workflowcore.engine.event;

import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import java.util.List;

/**
 * 늦게 구독한 클라이언트를 보완하기 위한 실행 진행 스냅샷.
 *
 * <p>구독 시점까지 완료된 노드 이벤트와, 이미 종료된 실행이면 종료 이벤트까지 포함한다.
 *
 * @param status   현재 실행 상태
 * @param terminal 실행이 이미 종료(SUCCESS/FAILED)되었는지 — true면 라이브 구독이 불필요하다
 * @param events   구독 시작 시 먼저 재생할 과거 이벤트 목록
 */
public record ExecutionEventSnapshot(
    ExecutionStatus status,
    boolean terminal,
    List<ExecutionEvent> events
) {
}
