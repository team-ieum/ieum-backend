package com.ieum.api.config;

import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.mcp.service.McpServerCatalogService;
import com.ieum.workflowcore.engine.executor.McpCatalogProvider;
import com.ieum.workflowcore.engine.executor.dto.McpServerRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * McpCatalogProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 mcp 패키지 모두에 접근하므로, workflow-core의 포트를
 * mcp 카탈로그 저장소와 연결한다. 이 빈이 등록되면 workflow-core의 StubMcpCatalogProvider는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMcpCatalogProvider implements McpCatalogProvider {

    private final McpServerCatalogRepository repository;
    private final McpServerCatalogService catalogService;

    @Override
    public List<McpServerRef> resolveServers(List<UUID> catalogIds, UUID userId) {
        if (catalogIds == null || catalogIds.isEmpty() || userId == null) {
            return List.of();
        }

        List<McpServerRef> result = new ArrayList<>();
        for (UUID catalogId : catalogIds) {
            McpServerCatalog catalog = repository.findByIdAndUserId(catalogId, userId).orElse(null);
            if (catalog == null) {
                log.warn("[DefaultMcpCatalogProvider] MCP 카탈로그 없음 또는 권한 없음 — userId: {}, catalogId: {}",
                        userId, catalogId);
                continue;
            }
            if (!catalog.isEnabled()) {
                log.warn("[DefaultMcpCatalogProvider] 비활성 MCP 서버 건너뜀 — catalogId: {}", catalogId);
                continue;
            }
            result.add(McpServerRef.builder()
                    .serverUrl(catalog.getServerUrl())
                    .headers(catalogService.decryptHeaders(catalog))
                    .build());
        }
        return result;
    }
}
