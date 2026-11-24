package com.ieum.api.chat.controller;

import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "채팅", description = """
    워크플로우 기반 AI 채팅. 전송은 WebSocket(STOMP)만 — REST 전송 없음.

    - 연결 `/ws` (STOMP over SockJS, 기존 WS 인증)
    - 전송 `/app/chat/{workflowId}`
    - 구독 `/user/queue/chat/stream`

    **요청 body**

    | 필드 | 필수 | 설명 |
    |---|---|---|
    | `prompt` | O | 2000자 이하 |
    | `currentNodes`·`currentEdges` | X | 현재 캔버스. null이면 새로 만들기 |
    | `sessionId` | X | null이면 새 세션 |
    | `credentialId` | X | 내 AI 키 ID. 비우면 최근 등록 키 |
    | `model` | X | `GET /api/v1/providers`의 `models[].id` 그대로, 100자 이하. 고르려면 `credentialId` 필수. 비우면 키 provider 기본 모델 |

    ```json
    {
      "prompt": "매일 아침 메일 요약을 슬랙으로 보내줘",
      "currentNodes": null,
      "currentEdges": null,
      "sessionId": null,
      "credentialId": "3f1c2a9e-0000-0000-0000-000000000000",
      "model": "claude-sonnet-5-5"
    }
    ```

    **응답 프레임** — `type`(소문자): `stage`(`stage`: designing) → `done`(`data`: 완성 응답) / `error`(`content`: 한국어 문구만, 에러 code 없음). `token`·`complete`는 설계 경로에서 쓰지 않는다.
    """)
@SecurityRequirement(name = "BearerAuth")
public interface ChatControllerDocs {

    @Operation(
        summary = "채팅 히스토리 조회",
        description = "세션의 메시지 목록을 최신순으로 페이지네이션하여 반환합니다."
    )
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<ChatResponse>>> getChatHistory(
        @Parameter(hidden = true) CustomUserDetails userDetails,
        @Parameter(description = "워크플로우 ID") UUID workflowId,
        @Parameter(description = "세션 ID") UUID sessionId,
        @Parameter(description = "페이지 번호 (0부터)") int page,
        @Parameter(description = "페이지 크기") int size
    );
}
