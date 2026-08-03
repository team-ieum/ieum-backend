package com.ieum.workflowcore.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 고립 {@code RUNNING} 실행 sweeper 설정.
 *
 * <p>{@code workflow.execution.stuck.*} — 임계를 코드 상수로 두지 않는 이유는
 * {@link RetryProperties}와 같다. 워크플로우가 무거워지면 정상 실행 시간이 늘어나므로
 * 배포 환경별로 올릴 수 있어야 한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "workflow.execution.stuck")
public class StuckExecutionProperties {

    /**
     * 이 시간을 넘겨 {@code RUNNING}에 머문 실행을 고립으로 본다.
     *
     * <p>기본 2시간은 정상 장시간 실행을 오판하지 않으려고 넉넉히 잡은 값이다. 런타임에
     * 전역 실행 타임아웃이 없어 상한을 계산으로 못 얻고, 대신 최악의 정상 실행을 추정한다 —
     * AI 노드 하나가 재시도(기본 3회)를 소진하면 agent 타임아웃(180초) × 3 + 백오프 ≈ 10분이고,
     * 병렬도 3에서 AI 노드 12개짜리 워크플로우가 전부 소진하면 4파 × 10분 ≈ 40분이다.
     * 여기에 약 3배 여유를 둔다.
     *
     * <p>내리기 전에 확인할 것: 잡 큐 회수({@code ieum.workflow.queue.reclaim-min-idle}, 기본 10분)로
     * 재실행될 실행을 이 sweeper가 먼저 FAILED로 확정하면 워커가 종료 상태로 보고 건너뛰어
     * <b>복구가 취소된다.</b> 재실행은 {@code startedAt}을 다시 찍으므로 임계는 누적이 아니라
     * 한 번의 실행 시간만 덮으면 되지만, 회수 지연(최대 11분)보다는 확실히 길어야 한다.
     */
    private Duration threshold = Duration.ofHours(2);
}
