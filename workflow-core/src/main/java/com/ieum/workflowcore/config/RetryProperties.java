package com.ieum.workflowcore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 노드 재시도 기본값. 노드 config에 {@code retry} 선언이 있으면 그쪽이 우선한다.
 *
 * <p>{@code workflow.execution.retry.*} — 장애 대응 시 재시도를 전역으로 끌 수 있도록
 * 코드 상수가 아니라 설정으로 둔다({@code ai-max-attempts: 1}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "workflow.execution.retry")
public class RetryProperties {

    /** AI 노드 기본 시도 횟수(최초 실행 포함). LLM rate-limit·timeout이 잦아 기본으로 켠다. */
    private int aiMaxAttempts = 3;

    /** AI 외 노드 기본 시도 횟수. 1 = 재시도 없음 — HTTP는 멱등성을 보장할 수 없다. */
    private int defaultMaxAttempts = 1;

    /** 첫 재시도 전 대기(ms) */
    private long backoffMs = 1000L;

    /** 회차당 대기 증가 배수 */
    private double multiplier = 2.0;

    /** 단일 대기 상한(ms) */
    private long maxBackoffMs = 30_000L;

    /** full jitter 적용 여부. 동시 실패한 fan-out 노드가 같은 순간에 재돌진하는 것을 막는다. */
    private boolean jitter = true;
}
