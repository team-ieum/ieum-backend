package com.ieum.api.webhookcredential.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebhookCredentialServiceTest {

    private WebhookCredentialRepository repository;
    private AesEncryptionService aes;
    private WebhookCredentialService service;

    private final UUID userId = UUID.randomUUID();
    private static final String URL = "https://discord.com/api/webhooks/123/abcDEF-secret";

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(WebhookCredentialRepository.class);
        aes = new AesEncryptionService("01234567890123456789012345678901"); // 32 bytes
        service = new WebhookCredentialService(repository, aes);
    }

    @Test
    void create_success_encryptsUrl() {
        when(repository.existsByUserIdAndDisplayName(userId, "내 채널")).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(0L);
        when(repository.save(any(WebhookCredential.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookCredential result = service.create(
                userId, WebhookProvider.DISCORD, "내 채널", URL, null);

        assertThat(result.getDisplayName()).isEqualTo("내 채널");
        assertThat(result.getProvider()).isEqualTo(WebhookProvider.DISCORD);
        assertThat(result.isEnabled()).isTrue();
        // URL은 평문이 아니라 암호화되어 저장되어야 함
        assertThat(result.getEncryptedWebhookUrl()).isNotNull();
        assertThat(result.getEncryptedWebhookUrl()).doesNotContain("secret");
        // 복호화하면 원본 URL로 복원
        assertThat(service.decryptWebhookUrl(result)).isEqualTo(URL);
    }

    @Test
    void create_duplicateName_throws() {
        when(repository.existsByUserIdAndDisplayName(userId, "중복")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                userId, WebhookProvider.SLACK, "중복", URL, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_DUPLICATE_NAME);

        verify(repository, never()).save(any());
    }

    @Test
    void create_limitExceeded_throws() {
        when(repository.existsByUserIdAndDisplayName(userId, "초과")).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(
                userId, WebhookProvider.SLACK, "초과", URL, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_LIMIT_EXCEEDED);

        verify(repository, never()).save(any());
    }

    @Test
    void getByIdAndUserId_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByIdAndUserId(id, userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_NOT_FOUND);
    }

    @Test
    void delete_existing_callsRepository() {
        UUID id = UUID.randomUUID();
        WebhookCredential credential = WebhookCredential.builder()
                .userId(userId).provider(WebhookProvider.SLACK).displayName("삭제대상")
                .encryptedWebhookUrl(aes.encrypt(URL)).enabled(true).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(credential));

        service.delete(id, userId);

        verify(repository).delete(credential);
    }
}
