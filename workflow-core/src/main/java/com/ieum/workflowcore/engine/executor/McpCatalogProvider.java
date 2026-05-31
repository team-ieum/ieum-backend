package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.engine.executor.dto.McpServerRef;
import java.util.List;
import java.util.UUID;

/**
 * 사용자별 MCP 서버 카탈로그를 조회하는 인터페이스.
 *
 * <p>workflow-core는 auth/api 모듈에 의존하지 않으므로 포트만 정의하고,
 * 실제 구현체는 api 모듈의 DefaultMcpCatalogProvider에 위치한다.
 */
public interface McpCatalogProvider {

    /**
     * catalogId 목록에 해당하는 MCP 서버 정보(serverUrl + 복호화된 헤더)를 조회한다.
     *
     * <p>해당 사용자 소유가 아니거나 존재하지 않는 catalogId, 비활성 서버는 결과에서 제외한다.
     * 조회 결과가 없으면 빈 리스트를 반환한다.
     *
     * @param catalogIds 노드 config에서 수집한 MCP 카탈로그 ID 목록
     * @param userId     실행 주체 사용자 ID
     * @return ieum-agent로 전달할 MCP 서버 참조 목록
     */
    List<McpServerRef> resolveServers(List<UUID> catalogIds, UUID userId);
}
