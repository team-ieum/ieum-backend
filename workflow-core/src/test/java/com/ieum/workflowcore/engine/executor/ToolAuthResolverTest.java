package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolAuthResolverTest {

    @Mock
    private CredentialProvider credentialProvider;

    @Mock
    private NotionTokenProvider notionTokenProvider;

    @InjectMocks
    private ToolAuthResolver toolAuthResolver;

    @Test
    @DisplayName("Notion 도구 사용 시 credentialId가 없으면 OAuth 연동 토큰을 반환한다")
    void resolveHeaders_NotionOAuth_Fallback() {
        // given
        UUID userId = UUID.randomUUID();
        String notionToken = "decrypted-notion-token";

        when(notionTokenProvider.getAccessToken(userId)).thenReturn(Optional.of(notionToken));

        List<Map<String, Object>> tools = List.of(
            Map.of("name", "builtin:notion_create_page")
        );

        // when
        Map<String, String> headers = toolAuthResolver.resolveHeaders(tools, userId);

        // then
        assertThat(headers).containsEntry("X-Notion-Token", notionToken);
    }

    @Test
    @DisplayName("Notion 도구 사용 시 credentialId가 있으면 BYOK 키를 우선 반환한다")
    void resolveHeaders_Notion_BYOK_Priority() {
        // given
        UUID userId = UUID.randomUUID();
        String byokKey = "byok-notion-key";
        String credentialId = "test-cred-id";

        when(credentialProvider.getDecryptedApiKey(credentialId, userId)).thenReturn(byokKey);

        List<Map<String, Object>> tools = List.of(
            Map.of("name", "builtin:notion_create_page", "credentialId", credentialId)
        );

        // when
        Map<String, String> headers = toolAuthResolver.resolveHeaders(tools, userId);

        // then
        assertThat(headers).containsEntry("X-Notion-Token", byokKey);
    }
}
