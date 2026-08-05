package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowResponse {

    private UUID id;
    private UUID userId;
    private String name;
    private String description;
    private boolean active;
    private TriggerType triggerType;
    private String cronExpression;
    private Integer version;
    private List<NodeView> nodes;
    private List<EdgeView> edges;

    /**
     * 이 워크플로우의 노드 config가 참조하는 웹훅 자격증명의 별칭 사전 (id 문자열 → displayName).
     *
     * <p>노드가 아니라 여기 둔 이유는 둘이다. (1) 웹훅 참조 자리가 노드 종류마다 다르다 — HTTP
     * 노드는 {@code config.webhookCredentialId} 하나지만 AI 노드는
     * {@code config.tools[].config.webhookCredentialId}로 도구마다 따로 있어, 노드에 문자열 필드
     * 하나를 붙이는 방식으로는 담기지 않는다. (2) {@link NodeView}의 JSON 모양은 요청
     * DTO({@link NodeDto})와 같아야 한다는 계약이 있어(IEUM-BE-60) 노드에 응답 전용 필드를 더할 수 없다.
     *
     * <p>키는 노드 config에 적힌 문자열 그대로다 — 프론트가 config에서 읽은 값으로 정규화 없이 바로
     * 찾을 수 있게 하기 위함이다. 요청 사용자 소유가 아니거나 없는 자격증명은 키 자체가 없다(별칭 유출
     * 방지). 참조가 없으면 빈 객체다.
     */
    @Schema(description = "노드가 참조하는 웹훅 자격증명 id → 별칭. 소유하지 않은 id는 실리지 않는다",
        example = "{\"3f1c...\": \"팀 슬랙\"}")
    private Map<String, String> webhookCredentialNames;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkflowResponse from(Workflow workflow, WorkflowVersion latestVersion,
            List<NodeView> nodes, List<EdgeView> edges,
            Map<String, String> webhookCredentialNames) {
        return WorkflowResponse.builder()
            .id(workflow.getId())
            .userId(workflow.getUserId())
            .name(workflow.getName())
            .description(workflow.getDescription())
            .active(workflow.isActive())
            .triggerType(workflow.getTriggerType())
            .cronExpression(workflow.getCronExpression())
            .version(latestVersion != null ? latestVersion.getVersion() : null)
            .nodes(nodes != null ? nodes : Collections.emptyList())
            .edges(edges != null ? edges : Collections.emptyList())
            .webhookCredentialNames(
                webhookCredentialNames != null ? webhookCredentialNames : Collections.emptyMap())
            .createdAt(workflow.getCreatedAt())
            .updatedAt(workflow.getUpdatedAt())
            .build();
    }
}
