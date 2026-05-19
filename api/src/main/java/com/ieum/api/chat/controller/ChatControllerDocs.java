package com.ieum.api.chat.controller;

import com.ieum.api.chat.dto.ChatRequest;
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

@Tag(name = "채팅", description = "워크플로우 기반 AI 채팅")
@SecurityRequirement(name = "BearerAuth")
public interface ChatControllerDocs {

    @Operation(
        summary = "채팅 메시지 전송",
        description = "워크플로우의 AI 노드에 메시지를 전송하고 응답을 받습니다. "
            + "sessionId를 생략하면 새 세션이 자동 생성됩니다."
    )
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<ChatResponse>> chat(
        @Parameter(hidden = true) CustomUserDetails userDetails,
        @Parameter(description = "워크플로우 ID") UUID workflowId,
        ChatRequest request
    );

    @Operation(
        summary = "채팅 히스토리 조회",
        description = "세션의 메시지 목록을 최신순으로 페이지네이션하여 반환합니다."
    )
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<ChatResponse>>> getChatHistory(
        @Parameter(hidden = true) CustomUserDetails userDetails,
        @Parameter(description = "세션 ID") UUID sessionId,
        @Parameter(description = "페이지 번호 (0부터)") int page,
        @Parameter(description = "페이지 크기") int size
    );
}
