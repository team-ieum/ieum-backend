package com.ieum.api.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ieum-agent {@code /v1/chat} 응답의 usage 역직렬화 계약 테스트.
 *
 * <p>agent(IEUM-AI-48)가 designer·reviewer의 모든 LLM 호출을 합산한 usage를 보내기 시작했다.
 * 이 매핑이 깨지면 베타 chat 토큰 차감이 조용히 비활성화되므로 실제 JSON으로 검증한다.
 */
class ChatAgentResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("응답 usage가 있으면 input/output 토큰을 실값으로 반환한다")
    void usage_mapsTokens() throws Exception {
        String json = """
            {
              "message": "워크플로우를 생성했습니다.",
              "type": "WORKFLOW_GENERATED",
              "usage": {"promptTokens": 1200, "completionTokens": 340, "totalTokens": 1540}
            }
            """;

        ChatAgentResponse response = objectMapper.readValue(json, ChatAgentResponse.class);

        assertThat(response.getInputTokens()).isEqualTo(1200);
        assertThat(response.getOutputTokens()).isEqualTo(340);
    }

    @Test
    @DisplayName("응답에 usage가 없으면 토큰은 null이다 (모델이 토큰을 보고하지 않은 경우)")
    void noUsage_returnsNull() throws Exception {
        String json = """
            {
              "message": "어느 채널로 보낼까요?",
              "type": "CLARIFICATION_NEEDED"
            }
            """;

        ChatAgentResponse response = objectMapper.readValue(json, ChatAgentResponse.class);

        assertThat(response.getInputTokens()).isNull();
        assertThat(response.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("usage가 명시적 null이어도 안전하게 null을 반환한다")
    void explicitNullUsage_returnsNull() throws Exception {
        String json = """
            {
              "message": "생성 완료",
              "type": "WORKFLOW_GENERATED",
              "usage": null
            }
            """;

        ChatAgentResponse response = objectMapper.readValue(json, ChatAgentResponse.class);

        assertThat(response.getInputTokens()).isNull();
        assertThat(response.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("usage에 totalTokens만 있어도 차감 기준값은 살아 있다")
    void partialUsage_totalTokensSurvives() throws Exception {
        String json = """
            {
              "message": "생성 완료",
              "type": "WORKFLOW_GENERATED",
              "usage": {"totalTokens": 900}
            }
            """;

        ChatAgentResponse response = objectMapper.readValue(json, ChatAgentResponse.class);

        assertThat(response.getInputTokens()).isNull();
        assertThat(response.getOutputTokens()).isNull();
        assertThat(response.getTotalTokens()).isEqualTo(900);
    }

    @Test
    @DisplayName("totalTokens는 입출력 합산이 아니라 agent가 보낸 값을 그대로 반환한다")
    void totalTokens_isNotDerived() throws Exception {
        // 캐시드·reasoning 토큰이 있는 프로바이더는 total != prompt + completion이다
        String json = """
            {
              "message": "생성 완료",
              "type": "WORKFLOW_GENERATED",
              "usage": {"promptTokens": 100, "completionTokens": 50, "totalTokens": 900}
            }
            """;

        ChatAgentResponse response = objectMapper.readValue(json, ChatAgentResponse.class);

        assertThat(response.getTotalTokens()).isEqualTo(900);
    }
}
