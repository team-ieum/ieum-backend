package com.ieum.api.mcp.controller;

import com.ieum.api.mcp.dto.CreateMcpServerRequest;
import com.ieum.api.mcp.dto.McpServerResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@Tag(name = "MCP 서버 카탈로그", description = "AI 노드가 사용할 MCP 서버 등록 및 관리")
@SecurityRequirement(name = "BearerAuth")
public interface McpServerCatalogControllerDocs {

    @Operation(summary = "MCP 서버 등록")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<McpServerResponse>> create(CustomUserDetails userDetails,
                                                          CreateMcpServerRequest request);

    @Operation(summary = "MCP 서버 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<McpServerResponse>>> getList(CustomUserDetails userDetails);

    @Operation(summary = "MCP 서버 삭제")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> delete(CustomUserDetails userDetails,
                                             @Parameter(description = "MCP 서버 ID") UUID id);
}
