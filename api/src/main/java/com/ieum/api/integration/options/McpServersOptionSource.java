package com.ieum.api.integration.options;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP 노드 {@code catalogId} 필드의 선택지 — 요청자의 활성 MCP 서버. 기준은 채팅 가용 목록
 * ({@code ChatService.resolveAvailableMcpServers})·실행({@code DefaultMcpCatalogProvider})과 같다.
 * 서버 URL·헤더는 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class McpServersOptionSource implements OptionSource {

    private final McpServerCatalogRepository repository;

    @Override
    public String key() {
        return "ieum.mcp_servers";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        List<OptionItem> items = repository.findByUserId(userId).stream()
            .filter(McpServerCatalog::isEnabled)
            .map(c -> new OptionItem(c.getId().toString(), c.getDisplayName()))
            .toList();
        return new OptionPage(items, null);
    }
}
