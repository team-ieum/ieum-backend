package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.engine.executor.dto.McpServerRef;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * McpCatalogProvider 임시 Stub 구현체.
 *
 * <p>api 모듈의 DefaultMcpCatalogProvider가 등록되면 {@code @ConditionalOnMissingBean}으로
 * 자동 대체된다. workflow-core 단독 실행(테스트, 모듈 빌드 등) 시에도 빈 주입 오류 없이
 * 기동할 수 있도록 빈 리스트를 반환한다(MCP 미주입).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = McpCatalogProvider.class, ignored = StubMcpCatalogProvider.class)
public class StubMcpCatalogProvider implements McpCatalogProvider {

    @Override
    public List<McpServerRef> resolveServers(List<UUID> catalogIds, UUID userId) {
        if (catalogIds != null && !catalogIds.isEmpty()) {
            log.warn("[StubMcpCatalogProvider] DefaultMcpCatalogProvider 미구현 — MCP 미주입. catalogIds: {}", catalogIds);
        }
        return List.of();
    }
}
