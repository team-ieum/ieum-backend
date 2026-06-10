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
     * AI 노드에서 Google 빌트인 도구 사용 시 Google Access Token 조회에 사용된다.
     */
    @Setter
    private UUID userId;

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
