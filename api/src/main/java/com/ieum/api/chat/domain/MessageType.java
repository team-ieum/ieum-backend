package com.ieum.api.chat.domain;

/**
 * 채팅 메시지 발신자 타입.
 */
public enum MessageType {
    /** 사용자가 보낸 메시지 */
    USER,
    /** AI 에이전트가 보낸 메시지 */
    AGENT,
    /** 시스템 메시지 (세션 시작/종료 등) */
    SYSTEM
}
