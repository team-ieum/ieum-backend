package com.ieum.api.chat.controller;

import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.service.ChatService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.chat.domain.ChatMessage;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 워크플로우 기반 채팅 REST API.
 *
 * <ul>
 *   <li>POST /api/v1/workflows/{workflowId}/chat — 메시지 전송 + AI 응답 (블로킹)</li>
 *   <li>GET  /api/v1/workflows/{workflowId}/chat/history — 세션 메시지 히스토리</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/workflows/{workflowId}/chat")
@RequiredArgsConstructor
public class ChatController implements ChatControllerDocs {

    private final ChatService chatService;

    /**
     * 메시지 전송 + AI 응답 반환.
     *
     * <p>request.sessionId가 null이면 새 세션을 생성한다.
     * 기존 세션을 이어가려면 이전 응답의 sessionId를 전달한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable UUID workflowId,
        @RequestBody @Valid ChatRequest request
    ) {
        ChatResponse response = chatService.chat(workflowId, userDetails.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 세션의 메시지 히스토리 조회 (최신순, 페이지네이션).
     *
     * <p>nextCursor는 다음 페이지 번호(문자열)이며, hasNext가 false이면 null로 반환된다.
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResponse<ChatResponse>>> getChatHistory(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable UUID workflowId,
        @RequestParam UUID sessionId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Page<ChatMessage> messagePage = chatService.getChatHistory(
            sessionId, userDetails.getId(), PageRequest.of(page, size)
        );

        List<ChatResponse> content = messagePage.getContent().stream()
            .map(m -> ChatResponse.fromHistory(m, sessionId))
            .toList();

        boolean hasNext = messagePage.hasNext();
        String nextCursor = hasNext ? String.valueOf(page + 1) : null;

        PageResponse<ChatResponse> pageResponse = PageResponse.of(content, hasNext, nextCursor);
        return ResponseEntity.ok(ApiResponse.ok(pageResponse));
    }
}
