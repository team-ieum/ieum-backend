package com.ieum.api.mcp.controller;

import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.dto.CreateMcpServerRequest;
import com.ieum.api.mcp.dto.McpServerResponse;
import com.ieum.api.mcp.service.McpServerCatalogService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp-servers")
@RequiredArgsConstructor
public class McpServerCatalogController {

    private final McpServerCatalogService service;

    @PostMapping
    public ResponseEntity<ApiResponse<McpServerResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid CreateMcpServerRequest request) {

        McpServerCatalog catalog = service.create(
                userDetails.getId(),
                request.displayName(),
                request.description(),
                request.serverUrl(),
                request.headers()
        );

        return ResponseEntity.status(201).body(ApiResponse.created(McpServerResponse.from(catalog)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<McpServerResponse>>> getList(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        List<McpServerResponse> responses = service.getByUserId(userDetails.getId())
                .stream()
                .map(McpServerResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(responses));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        service.delete(id, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
