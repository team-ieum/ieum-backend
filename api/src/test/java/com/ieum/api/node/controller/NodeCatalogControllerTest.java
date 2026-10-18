package com.ieum.api.node.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.common.GlobalExceptionHandler;
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
    @DisplayName("agent 카탈로그를 data에 그대로 — 중첩 fixed(event·brand)와 false 값도 보존")
    void wrapsAgentCatalogVerbatim() throws Exception {
        given(agentClient.getNodeCatalog()).willReturn(new ObjectMapper().readTree("""
            {"entries":[{"id":"schedule.every","kind":"trigger","provider":"ieum","app":"schedule","nodeType":"TRIGGER",
              "fixed":{"type":"TRIGGER","event":"schedule.every","config":{"brand":"webhook"}},
              "inputFields":[{"key":"cron","title":"실행 주기","type":"cron","required":true,"ref":false}]}]}
            """));

        mockMvc.perform(get("/api/v1/nodes/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.entries[0].id").value("schedule.every"))
            .andExpect(jsonPath("$.data.entries[0].kind").value("trigger"))
            .andExpect(jsonPath("$.data.entries[0].fixed.event").value("schedule.every"))
            .andExpect(jsonPath("$.data.entries[0].fixed.config.brand").value("webhook"))
            .andExpect(jsonPath("$.data.entries[0].inputFields[0].ref").value(false));
    }
}
