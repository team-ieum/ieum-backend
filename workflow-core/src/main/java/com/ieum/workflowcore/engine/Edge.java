package com.ieum.workflowcore.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 워크플로우 버전의 edgesJson에서 역직렬화되는 노드 간 연결 정의 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Edge {

    private String source;
    private String target;
    /** CONDITION 노드의 분기 타입 — "true" 또는 "false". 일반 엣지는 null */
    private String conditionType;
}
