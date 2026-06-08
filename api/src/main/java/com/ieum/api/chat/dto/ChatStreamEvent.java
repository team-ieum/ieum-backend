package com.ieum.api.chat.dto;

/**
 * ieum-agent {@code /v1/chat/stream} SSE 이벤트를 구조화한 내부 전달 객체.
 *
 * <p>{@code AgentClient.chatStream()}이 SSE 프레임을 파싱해 이 타입으로 방출하고,
 * {@code WebSocketChatHandler}가 type에 따라 분기 처리한다.
 *
 * <ul>
 *   <li>{@link EventType#STAGE} — 진행 단계 ({@code stage}: designing/reviewing)</li>
 *   <li>{@link EventType#DONE} — 완성된 응답 ({@code response}: ChatAgentResponse)</li>
 *   <li>{@link EventType#ERROR} — 오류 ({@code errorMessage})</li>
 * </ul>
 */
public record ChatStreamEvent(
    EventType type,
    String stage,
    ChatAgentResponse response,
    String errorMessage
) {

    public enum EventType {
        STAGE, DONE, ERROR
    }

    public static ChatStreamEvent stage(String stage) {
        return new ChatStreamEvent(EventType.STAGE, stage, null, null);
    }

    public static ChatStreamEvent done(ChatAgentResponse response) {
        return new ChatStreamEvent(EventType.DONE, null, response, null);
    }

    public static ChatStreamEvent error(String errorMessage) {
        return new ChatStreamEvent(EventType.ERROR, null, null, errorMessage);
    }
}
