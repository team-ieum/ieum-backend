package com.ieum.api.workflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ieum.workflowcore.service.NodeTestResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * 노드 테스트 결과 또는 최신 샘플. 상태는 SUCCESS·FAILED, webhook 트리거는 LISTENING이다.
 * 값이 없는 필드는 응답에서 빠진다(FAILED는 output 없음, LISTENING은 output·testedAt 없음).
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeTestResponse {

    @Schema(description = "SUCCESS | FAILED | LISTENING")
    private final String status;

    @Schema(description = "마스킹된 출력(SUCCESS). 민감 키(token·apiKey 등)는 ***다.")
    private final Map<String, Object> output;

    @Schema(description = "실패 메시지(FAILED)")
    private final String error;

    @Schema(description = "테스트 시각. webhook 대기 후 샘플 폴링에서 이전 샘플과 구별하는 기준으로 쓴다.")
    private final LocalDateTime testedAt;

    @Schema(description = "LISTENING일 때 외부 시스템이 POST할 경로(/api/v1/webhooks/{workflowId}). 호스트는 클라이언트가 붙인다.")
    private final String webhookUrl;

    @Schema(description = "LISTENING 만료 시각(시작 후 5분)")
    private final LocalDateTime expiresAt;

    public static NodeTestResponse from(NodeTestResult result) {
        return NodeTestResponse.builder()
            .status(result.status().name())
            .output(result.output())
            .error(result.error())
            .testedAt(result.testedAt())
            .build();
    }

    public static NodeTestResponse listening(UUID workflowId, LocalDateTime expiresAt) {
        return NodeTestResponse.builder()
            .status("LISTENING")
            .webhookUrl("/api/v1/webhooks/" + workflowId)
            .expiresAt(expiresAt)
            .build();
    }
}
