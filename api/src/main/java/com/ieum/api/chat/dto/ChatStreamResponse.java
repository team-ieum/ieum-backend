package com.ieum.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebSocket 스트리밍 응답 DTO.
 *
 * <p>type에 따라 다른 필드가 사용된다:
 * <ul>
 *   <li>{@link StreamType#STAGE} — chat 설계 진행 단계 (stage: designing/reviewing)</li>
 *   <li>{@link StreamType#DONE} — 설계 완료. 완성된 응답(data: message/nodes/edges 등) 포함</li>
 *   <li>{@link StreamType#TOKEN} — AI가 토큰 단위로 생성한 텍스트 조각 (content, index 포함)</li>
 *   <li>{@link StreamType#COMPLETE} — 스트리밍 완료 신호 (totalTokens 포함)</li>
 *   <li>{@link StreamType#ERROR} — 에러 발생 (content에 에러 메시지)</li>
 * </ul>
 *
 * <p>chat 설계 스트리밍(BE↔Agent SSE)은 STAGE → DONE 순서로 전송한다. TOKEN/COMPLETE는
 * 토큰 단위 스트리밍용으로 남겨둔 하위 호환 타입이며 설계 경로에서는 사용하지 않는다.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatStreamResponse {

    /** 응답 타입. JSON 직렬화 시 소문자 문자열로 출력. */
    private final StreamType type;

    /** 토큰 텍스트 조각 (type=TOKEN) 또는 에러 메시지 (type=ERROR) */
    private final String content;

    /** 토큰 순서 인덱스 (type=TOKEN) */
    private final Integer index;

    /** 전체 생성 토큰 수 (type=COMPLETE) */
    private final Integer totalTokens;

    /** 진행 단계 — "designing" | "reviewing" (type=STAGE) */
    private final String stage;

    /** 완성된 응답 — message/nodes/edges 등 (type=DONE) */
    private final ChatResponse data;

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

    public static ChatStreamResponse stage(String stage) {
        return ChatStreamResponse.builder()
            .type(StreamType.STAGE)
            .stage(stage)
            .build();
    }

    public static ChatStreamResponse done(ChatResponse data) {
        return ChatStreamResponse.builder()
            .type(StreamType.DONE)
            .data(data)
            .build();
    }

    /** 스트리밍 응답 타입. {@link JsonValue}로 소문자 문자열로 직렬화된다. */
    @Getter
    @RequiredArgsConstructor
    public enum StreamType {
        TOKEN("token"),
        STAGE("stage"),
        DONE("done"),
        COMPLETE("complete"),
        ERROR("error");

        @JsonValue
        private final String value;
    }
}
