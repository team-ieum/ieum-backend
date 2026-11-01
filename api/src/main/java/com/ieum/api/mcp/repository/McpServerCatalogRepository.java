package com.ieum.api.mcp.repository;

import com.ieum.api.mcp.domain.McpServerCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpServerCatalogRepository extends JpaRepository<McpServerCatalog, UUID> {

    List<McpServerCatalog> findByUserId(UUID userId);

    Optional<McpServerCatalog> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndDisplayName(UUID userId, String displayName);

    long countByUserId(UUID userId);

    /** 회원 탈퇴 시 호출자 트랜잭션 안에서 부른다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM McpServerCatalog e WHERE e.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
