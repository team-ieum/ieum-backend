package com.ieum.workflowcore.engine;

import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 워크플로우 버전의 nodesJson에서 역직렬화되는 노드 정의 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Node {

    private String id;
    private NodeType type;
    private String label;
    /** 노드 실행에 필요한 설정값 (프롬프트, URL, 조건식 등) */
    private Map<String, Object> config;
    /** 카탈로그 이벤트 이름(예: github.create_issue). 노드 종류의 진실원이다 (IEUM-BE-92) */
    private String event;

    /** event 없이 만드는 노드. 테스트와 합성 노드가 쓴다. */
    public Node(String id, NodeType type, String label, Map<String, Object> config) {
        this(id, type, label, config, null);
    }
}
