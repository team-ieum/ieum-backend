package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 노드 실행 시 ieum-agent로 전달하는 커스텀 MCP 서버 정보.
 *
 * <p>ieum-agent의 McpServerConfig(server_url, headers)와 JSON 형태가 일치해야 하므로
 * snake_case 키({@code server_url})로 직렬화한다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpServerRef {

    @JsonProperty("server_url")
    private String serverUrl;

    private Map<String, String> headers;
}
