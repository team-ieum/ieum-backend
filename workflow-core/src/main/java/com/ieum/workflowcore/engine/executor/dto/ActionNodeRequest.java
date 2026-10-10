package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ieum-agent {@code POST /v1/actions/execute} 요청 본문. 필드 이름(camelCase)이 agent 계약이다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionNodeRequest {

    private String nodeId;
    /** 실행할 도구 키 — ACTION 노드 {@code tools[0].name} (예: {@code builtin:github_create_issue}) */
    private String toolKey;
    /**
     * {@code tools[0].config} — 참조식이 치환되고 slack·discord 웹훅 URL({@code webhook_url})이 주입된 복사본.
     * 함수 인자 밖의 키(웹훅 자격증명 ID, 표시 이름 등)도 섞여 있으며 agent가 시그니처로 거른다.
     */
    private Map<String, Object> config;
}
