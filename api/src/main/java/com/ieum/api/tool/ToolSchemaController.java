package com.ieum.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolSchemaController implements ToolSchemaControllerDocs {

    private final AgentClient agentClient;

    @Override
    @GetMapping("/schema")
    public ResponseEntity<ApiResponse<JsonNode>> getToolSchema() {
        return ResponseEntity.ok(ApiResponse.ok(agentClient.getToolSchema()));
    }
}
