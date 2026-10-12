package com.ieum.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.api.webhook.service.WebhookService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 시큐리티 체인·서블릿 필터를 태운다 — JWT 없이 열리는지(permitAll), 그리고 form 본문이
 * 앞단 필터({@code getParameter}·FormContentFilter)에 먹히지 않고 리더까지 오는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class HookReceiveIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WebhookService webhookService;

    @SuppressWarnings("unchecked")
    private Map<String, Object> received() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webhookService).receive(eq("tok"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("JWT 없이 POST form → 200, 본문·쿼리가 payload로")
    void permitAll_formPost() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/tok?q=1")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("a=1&a=2"))
            .andExpect(status().isOk());

        assertThat(received()).isEqualTo(Map.of("q", "1", "a", List.of("1", "2")));
    }

    @Test
    @DisplayName("JWT 없이 PUT JSON → 200")
    void permitAll_putJson() throws Exception {
        mockMvc.perform(put("/api/v1/hooks/tok").contentType(MediaType.APPLICATION_JSON).content("{\"x\": true}"))
            .andExpect(status().isOk());

        assertThat(received()).isEqualTo(Map.of("x", true));
    }

    @Test
    @DisplayName("PUT form도 본문이 리더까지 온다(FormContentFilter가 먹지 않는다), 본문 키 우선")
    void putForm_bodyNotConsumedByFilter() throws Exception {
        mockMvc.perform(put("/api/v1/hooks/tok?q=1")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("a=1&q=2"))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("q", "2", "a", "1"));
    }
}
