package com.ieum.api.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우 채팅 요청 DTO.
 *
 * <p>프론트엔드는 현재 캔버스 상태({@code currentNodes}/{@code currentEdges})를 함께 전송한다.
 * {@code currentNodes}가 null이면 신규 워크플로우 생성, 값이 있으면 기존 워크플로우 수정으로 판단한다.
 *
 * <p>단, ieum-backend는 프론트엔드 로컬 상태를 직접 신뢰하지 않으며,
 * DB의 최신 workflow canonical state를 기준으로 agent에 전달한다.
 */
@Getter
@NoArgsConstructor
public class ChatRequest {

    /** 사용자 자연어 입력 */
    @NotBlank(message = "메시지를 입력해주세요.")
    @Size(max = 2000, message = "메시지는 2000자를 초과할 수 없습니다.")
    private String prompt;

    /**
     * 현재 캔버스 노드 목록.
     * null → 신규 워크플로우 생성 요청
     * non-null → 기존 워크플로우 수정 요청
     */
    private List<Object> currentNodes;

    /** 현재 캔버스 엣지 목록 (currentNodes와 함께 전달) */
    private List<Object> currentEdges;

    /**
     * 기존 세션에 이어서 대화할 경우 전달.
     * null이면 새 세션 자동 생성.
     */
    private UUID sessionId;

    /**
     * AI 노드가 없는 워크플로우(빈 워크플로우)에서 채팅 시작 시 사용할 Credential ID.
     * 워크플로우에 AI 노드가 이미 있으면 해당 노드의 설정을 우선 사용하고,
     * AI 노드가 없을 때만 이 값으로 fallback한다.
     */
    private UUID credentialId;
}
