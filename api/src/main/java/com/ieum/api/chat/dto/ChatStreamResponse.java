package com.ieum.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebSocket 스트리밍 응답 DTO.
 *
 * <p>type에 따라 다른 필드가 사용된다:
 * <ul>
 *   <li>{@link StreamType#TOKEN} — AI가 토큰 단위로 생성한 텍스트 조각 (content, index 포함)</li>
 *   <li>{@link StreamType#COMPLETE} — 스트리밍 완료 신호 (totalTokens 포함)</li>
 *   <li>{@link StreamType#ERROR} — 에러 발생 (content에 에러 메시지)</li>
 * </ul>
 */
@Getter
@Builder
public class ChatStreamResponse {

    /** 응답 타입. JSON 직렬화 시 소문자 문자열로 출력 ("token" | "complete" | "error"). */
    private final StreamType type;

    /** 토큰 텍스트 조각 (type=TOKEN) 또는 에러 메시지 (type=ERROR) */
    private final String content;

    /** 토큰 순서 인덱스 (type=TOKEN) */
    private final Integer index;

    /** 전체 생성 토큰 수 (type=COMPLETE) */
    private final Integer totalTokens;

    public static ChatStreamResponse token(String content, int index) {
        return ChatStreamResponse.builder()
            .type(StreamType.TOKEN)
            .content(content)
            .index(index)
            .build();
    }

    public static ChatStreamResponse complete(int totalTokens) {
        return ChatStreamResponse.builder()
            .type(StreamType.COMPLETE)
            .totalTokens(totalTokens)
            .build();
    }

    public static ChatStreamResponse error(String message) {
        return ChatStreamResponse.builder()
            .type(StreamType.ERROR)
            .content(message)
            .build();
    }

    /** 스트리밍 응답 타입. {@link JsonValue}로 소문자 문자열로 직렬화된다. */
    @Getter
    @RequiredArgsConstructor
    public enum StreamType {
        TOKEN("token"),
        COMPLETE("complete"),
        ERROR("error");

        @JsonValue
        private final String value;
    }
}
