package com.ieum.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "도구 스키마", description = "AI 노드 도구 설정 폼 스키마")
@SecurityRequirement(name = "BearerAuth")
public interface ToolSchemaControllerDocs {

    @Operation(
        summary = "도구 필드 스키마 조회",
        description = "노드 tools[].name별 설정 필드 목록(name/title/description/type/required, "
            + "드롭다운이면 optionsSource·optionsInputs)을 반환합니다. ieum-agent 스키마를 그대로 전달하며 캐시하지 않습니다. "
            + "필드 키가 노드 tools[].config에 없으면 AI가 결정, 있으면 그 값으로 고정됩니다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<JsonNode>> getToolSchema();
}
