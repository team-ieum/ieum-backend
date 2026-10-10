package com.ieum.api.workflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 노드 테스트 요청 — 둘 다 선택. 본문 자체를 생략해도 된다(저장된 노드를 테스트). */
@Getter
@NoArgsConstructor
public class NodeTestRequest {

    @Valid
    @Schema(description = "편집 중인 노드 정의. 없으면 저장된 최신 버전의 노드. id는 경로의 nodeId와 같아야 한다(다르면 400).")
    private NodeDto node;

    @Schema(description = "MANUAL 트리거 테스트의 입력 JSON. 트리거가 아니거나 SCHEDULE이면 무시된다.")
    private Map<String, Object> input;
}
