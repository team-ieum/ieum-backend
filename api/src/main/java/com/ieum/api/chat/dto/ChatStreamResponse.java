package com.ieum.api.chat.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * WebSocket 스트리밍 응답 DTO.
 *
 * <p>type에 따라 다른 필드가 사용된다:
 * <ul>
 *   <li>{@code token} — AI가 토큰 단위로 생성한 텍스트 조각 (content, index 포함)</li>
 *   <li>{@code complete} — 스트리밍 완료 신호 (totalTokens 포함)</li>
 *   <li>{@code error} — 에러 발생 (content에 에러 메시지)</li>
 * </ul>
 */
@Getter
@Builder
public class ChatStreamResponse {

    /** 응답 타입: "token" | "complete" | "error" */
    private final String type;

    /** 토큰 텍스트 조각 (type=token) 또는 에러 메시지 (type=error) */
    private final String content;

    /** 토큰 순서 인덱스 (type=token) */
    private final Integer index;

    /** 전체 생성 토큰 수 (type=complete) */
    private final Integer totalTokens;

    public static ChatStreamResponse token(String content, int index) {
        return ChatStreamResponse.builder()
            .type("token")
            .content(content)
            .index(index)
            .build();
    }

    public static ChatStreamResponse complete(int totalTokens) {
        return ChatStreamResponse.builder()
            .type("complete")
            .totalTokens(totalTokens)
            .build();
    }

    public static ChatStreamResponse error(String message) {
        return ChatStreamResponse.builder()
            .type("error")
            .content(message)
            .build();
    }
}
