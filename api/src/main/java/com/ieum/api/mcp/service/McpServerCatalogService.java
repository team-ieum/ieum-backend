package com.ieum.api.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpServerCatalogService {

    private static final int MAX_MCP_SERVERS_PER_USER = 20;

    private final McpServerCatalogRepository repository;
    private final AesEncryptionService aesEncryptionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public McpServerCatalog create(UUID userId, String displayName, String description,
                                   String serverUrl, Map<String, String> headers) {
        if (repository.existsByUserIdAndDisplayName(userId, displayName)) {
            throw new CustomException(ErrorCode.MCP_SERVER_DUPLICATE_NAME);
        }
        if (repository.countByUserId(userId) >= MAX_MCP_SERVERS_PER_USER) {
            throw new CustomException(ErrorCode.MCP_SERVER_LIMIT_EXCEEDED,
                    "MCP 서버는 최대 " + MAX_MCP_SERVERS_PER_USER + "개까지 등록할 수 있습니다.");
        }

        String encryptedHeaders = encryptHeaders(headers);

        McpServerCatalog catalog = McpServerCatalog.builder()
                .userId(userId)
                .displayName(displayName)
                .description(description)
                .serverUrl(serverUrl)
                .encryptedHeaders(encryptedHeaders)
                .enabled(true)
                .build();

        return repository.save(catalog);
    }

    public List<McpServerCatalog> getByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    public McpServerCatalog getByIdAndUserId(UUID id, UUID userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.MCP_SERVER_NOT_FOUND));
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        McpServerCatalog catalog = getByIdAndUserId(id, userId);
        repository.delete(catalog);
    }

    /**
     * 실행 주입용: catalogId로 복호화된 헤더 맵을 조회한다. 헤더가 없으면 빈 맵을 반환한다.
     * (workflow-core가 mcp 도구 실행 시 serverUrl과 함께 사용)
     */
    public Map<String, String> decryptHeaders(McpServerCatalog catalog) {
        if (catalog.getEncryptedHeaders() == null || catalog.getEncryptedHeaders().isBlank()) {
            return Map.of();
        }
        try {
            String json = aesEncryptionService.decrypt(catalog.getEncryptedHeaders());
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String encryptHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return aesEncryptionService.encrypt(objectMapper.writeValueAsString(headers));
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
