package com.ieum.api.mcp.repository;

import com.ieum.api.mcp.domain.McpServerCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerCatalogRepository extends JpaRepository<McpServerCatalog, UUID> {

    List<McpServerCatalog> findByUserId(UUID userId);

    Optional<McpServerCatalog> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndDisplayName(UUID userId, String displayName);

    long countByUserId(UUID userId);
}
