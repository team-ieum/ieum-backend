package com.ieum.api.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class McpServerCatalogServiceTest {

    private McpServerCatalogRepository repository;
    private AesEncryptionService aes;
    private ObjectMapper objectMapper;
    private McpServerCatalogService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(McpServerCatalogRepository.class);
        aes = new AesEncryptionService("01234567890123456789012345678901"); // 32 bytes
        objectMapper = new ObjectMapper();
        service = new McpServerCatalogService(repository, aes, objectMapper);
    }

    @Test
    @DisplayName("성공 - 헤더 암호화 저장")
    void create_success_encryptsHeaders() {
        when(repository.existsByUserIdAndDisplayName(userId, "내 MCP")).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(0L);
        when(repository.save(any(McpServerCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        McpServerCatalog result = service.create(
                userId, "내 MCP", "테스트 서버", "https://mcp.example.com/sse",
                Map.of("Authorization", "Bearer secret-token"));

        assertThat(result.getDisplayName()).isEqualTo("내 MCP");
        assertThat(result.getServerUrl()).isEqualTo("https://mcp.example.com/sse");
        assertThat(result.isEnabled()).isTrue();
        // 헤더는 평문이 아니라 암호화되어 저장되어야 함
        assertThat(result.getEncryptedHeaders()).isNotNull();
        assertThat(result.getEncryptedHeaders()).doesNotContain("secret-token");
        // 복호화하면 원본 헤더로 복원
        assertThat(service.decryptHeaders(result))
                .containsEntry("Authorization", "Bearer secret-token");
    }

    @Test
    @DisplayName("헤더 없이 생성 시 null 저장")
    void create_withoutHeaders_storesNull() {
        when(repository.existsByUserIdAndDisplayName(userId, "헤더없음")).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(0L);
        when(repository.save(any(McpServerCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        McpServerCatalog result = service.create(
                userId, "헤더없음", null, "https://mcp.example.com/sse", null);

        assertThat(result.getEncryptedHeaders()).isNull();
        assertThat(service.decryptHeaders(result)).isEmpty();
    }

    @Test
    @DisplayName("중복 이름 - 예외 발생")
    void create_duplicateName_throws() {
        when(repository.existsByUserIdAndDisplayName(userId, "중복")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                userId, "중복", null, "https://mcp.example.com/sse", null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MCP_SERVER_DUPLICATE_NAME);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("등록 한도 초과 - 예외 발생")
    void create_limitExceeded_throws() {
        when(repository.existsByUserIdAndDisplayName(userId, "초과")).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(
                userId, "초과", null, "https://mcp.example.com/sse", null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MCP_SERVER_LIMIT_EXCEEDED);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("조회 없음 - 예외 발생")
    void getByIdAndUserId_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByIdAndUserId(id, userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MCP_SERVER_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제 - 리포지토리 정상 호출")
    void delete_existing_callsRepository() {
        UUID id = UUID.randomUUID();
        McpServerCatalog catalog = McpServerCatalog.builder()
                .userId(userId).displayName("삭제대상").serverUrl("https://x").enabled(true).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(catalog));

        service.delete(id, userId);

        verify(repository).delete(catalog);
    }
}
