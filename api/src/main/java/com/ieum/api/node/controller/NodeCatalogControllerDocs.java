package com.ieum.api.node.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "노드 카탈로그", description = "노드 종류별 입력·출력 필드 정의 (빌더 폼·매핑)")
@SecurityRequirement(name = "BearerAuth")
public interface NodeCatalogControllerDocs {

    @Operation(
        summary = "노드 카탈로그 조회",
        description = "빌더에 추가할 수 있는 노드 종류(entries)별 id·nodeType·app·title·description·match·fixed(해당 시 outputDynamic·outputsFrom)와 "
            + "inputFields·outputFields(key/title/type/description/path/required/default/choices/optionsSource/optionsInputs/list/"
            + "children/dynamic/ref)를 반환합니다. ieum-agent 카탈로그를 그대로 전달하며 캐시하지 않습니다. "
            + "match는 저장된 노드를 항목에 연결하는 규칙, fixed는 새 노드의 뼈대, path는 값의 노드 JSON 위치입니다. "
            + "optionsSource는 GET /api/v1/integrations/{app}/options/{resource}로 조회합니다. "
            + "값이 비면(키 없음) 액션은 default를 쓰고, AI 에이전트의 도구 필드는 AI가 결정합니다. "
            + "오류 code: PROVIDER_ERROR(502), PROVIDER_TIMEOUT(504), PROVIDER_RATE_LIMITED(429).")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<JsonNode>> getNodeCatalog();
}
