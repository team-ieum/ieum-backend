package com.ieum.api.tool;

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

class ToolSchemaControllerTest {

    private final AgentClient agentClient = mock(AgentClient.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ToolSchemaController(agentClient))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    @DisplayName("agent 스키마를 ApiResponse data에 그대로 싣는다")
    void wrapsAgentSchema() throws Exception {
        given(agentClient.getToolSchema()).willReturn(new ObjectMapper().readTree(
            "{\"tools\":[{\"name\":\"builtin:google_sheets_read\",\"fields\":[{\"name\":\"sheet_name\","
                + "\"optionsSource\":\"google.worksheets\",\"optionsInputs\":[\"spreadsheet_id\"]}]}]}"));

        mockMvc.perform(get("/api/v1/tools/schema"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.tools[0].name").value("builtin:google_sheets_read"))
            .andExpect(jsonPath("$.data.tools[0].fields[0].optionsInputs[0]").value("spreadsheet_id"));
    }
}
