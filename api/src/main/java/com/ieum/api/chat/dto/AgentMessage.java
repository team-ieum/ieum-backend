package com.ieum.api.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ieum-agent로 전달하는 대화 메시지 단위.
 * OpenAI/Claude API의 messages 배열 형식을 따른다.
 */
@Getter
@AllArgsConstructor
public class AgentMessage {

    /** "user" 또는 "assistant" */
    private final String role;

    private final String content;

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content);
    }
}
