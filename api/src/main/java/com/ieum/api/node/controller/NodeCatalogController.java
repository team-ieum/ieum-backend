package com.ieum.api.node.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nodes")
@RequiredArgsConstructor
public class NodeCatalogController implements NodeCatalogControllerDocs {

    private final AgentClient agentClient;

    @Override
    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<JsonNode>> getNodeCatalog() {
        return ResponseEntity.ok(ApiResponse.ok(agentClient.getNodeCatalog()));
    }
}
