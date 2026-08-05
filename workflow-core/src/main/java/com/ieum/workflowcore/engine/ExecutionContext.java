package com.ieum.workflowcore.engine;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 워크플로우 단일 실행 동안 노드 간에 공유되는 컨텍스트.
 * 각 노드의 output이 여기에 저장되어 변수 치환({@code {{nodes.<id>.output.<field>}}})의 소스가 된다.
 *
 * <p>fan-out 병렬 실행 시 워커 스레드(노드 실행)가 {@code getNodeOutput}으로 읽는 동안
 * 메인 스레드가 {@code setNodeOutput}으로 쓰므로, {@code nodeOutputs}는 {@link ConcurrentHashMap}이다.
 */
@Getter
@NoArgsConstructor
public class ExecutionContext {

    /**
     * 워크플로우 소유자 ID.
     * 실행 시점에 사용자별 자격증명을 조회하는 데 쓰인다
     * (AI 노드의 Google Access Token·웹훅 URL, HTTP 노드의 웹훅 URL 등).
     */
    @Setter
    private UUID userId;

    /**
     * 실행 단위 trace 상관관계 ID(32자 무하이픈 hex).
     * AI 노드가 agent 호출 시 X-Trace-Id 헤더로 전달해 Phoenix span의 ieum.trace_id와 조인한다.
     */
    @Setter
    private String traceId;

    /** key: nodeId, value: 해당 노드의 output Map */
    private final Map<String, Map<String, Object>> nodeOutputs = new ConcurrentHashMap<>();

    public void setNodeOutput(String nodeId, Map<String, Object> output) {
        // ConcurrentHashMap은 null 값을 허용하지 않으므로 빈 Map으로 보정한다.
        this.nodeOutputs.put(nodeId, output != null ? output : Collections.emptyMap());
    }

    public Map<String, Object> getNodeOutput(String nodeId) {
        return this.nodeOutputs.getOrDefault(nodeId, Collections.emptyMap());
    }
}
