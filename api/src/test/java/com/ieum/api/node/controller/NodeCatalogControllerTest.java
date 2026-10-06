package com.ieum.api.node.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** agent 노드 카탈로그를 손대지 않고 ApiResponse에 싣는지 고정한다 (IEUM-BE-75). 인증 필수는 SecurityConfig가 맡는다. */
class NodeCatalogControllerTest {

    private final AgentClient agentClient = mock(AgentClient.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NodeCatalogController(agentClient))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    @DisplayName("agent 카탈로그를 data에 그대로 — null·false 값과 점이 든 키도 보존")
    void wrapsAgentCatalogVerbatim() throws Exception {
        given(agentClient.getNodeCatalog()).willReturn(new ObjectMapper().readTree("""
            {"entries":[{"id":"trigger.schedule","nodeType":"TRIGGER","app":null,
              "match":{"type":"TRIGGER","config.triggerType":"SCHEDULE"},
              "inputFields":[{"key":"cron","path":"config.cron","type":"cron","required":true,"ref":false}]}]}
            """));

        mockMvc.perform(get("/api/v1/nodes/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.entries[0].id").value("trigger.schedule"))
            .andExpect(jsonPath("$.data.entries[0].match['config.triggerType']").value("SCHEDULE"))
            .andExpect(jsonPath("$.data.entries[0].inputFields[0].path").value("config.cron"))
            .andExpect(jsonPath("$.data.entries[0].inputFields[0].ref").value(false))
            .andExpect(content().string(containsString("\"app\":null")));
    }

    @Test
    @DisplayName("agent 실패는 AgentClient가 고른 code·상태로 나간다 (PROVIDER_ERROR → 502)")
    void agentFailureKeepsCode() throws Exception {
        given(agentClient.getNodeCatalog()).willThrow(new CustomException(ErrorCode.PROVIDER_ERROR));

        mockMvc.perform(get("/api/v1/nodes/catalog"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PROVIDER_ERROR"));
    }
}
