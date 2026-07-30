package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.common.util.AesEncryptionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** {@code resolveAlertWebhookUrl} — 실패 알림 대상 웹훅 조회. */
class DefaultWebhookCredentialProviderAlertTest {

    private static final String URL = "https://discord.com/api/webhooks/123/alertSecret";

    private WebhookCredentialRepository repository;
    private AesEncryptionService aes;
    private DefaultWebhookCredentialProvider provider;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(WebhookCredentialRepository.class);
        aes = new AesEncryptionService("01234567890123456789012345678901");
        provider = new DefaultWebhookCredentialProvider(
                repository, new WebhookCredentialService(repository, aes));
    }

    private WebhookCredential credential(WebhookProvider webhookProvider, boolean enabled) {
        WebhookCredential credential = WebhookCredential.builder()
                .userId(userId)
                .provider(webhookProvider)
                .displayName("알림 채널")
                .encryptedWebhookUrl(aes.encrypt(URL))
                .enabled(enabled)
                .build();
        credential.changeAlertTarget(true);
        ReflectionTestUtils.setField(credential, "id", UUID.randomUUID());
        return credential;
    }

    @Test
    @DisplayName("알림 대상으로 지정된 활성 DISCORD 웹훅의 복호화 URL을 반환한다")
    void resolveAlertWebhookUrl_returnsDecryptedUrl() {
        when(repository.findByUserIdAndProviderAndAlertTargetTrue(userId, WebhookProvider.DISCORD))
                .thenReturn(List.of(credential(WebhookProvider.DISCORD, true)));

        assertThat(provider.resolveAlertWebhookUrl(userId)).contains(URL);
    }

    @Test
    @DisplayName("지정이 없으면 빈 Optional — 소유자 발신을 생략한다")
    void resolveAlertWebhookUrl_noTarget_returnsEmpty() {
        when(repository.findByUserIdAndProviderAndAlertTargetTrue(userId, WebhookProvider.DISCORD))
                .thenReturn(List.of());

        assertThat(provider.resolveAlertWebhookUrl(userId)).isEmpty();
    }

    @Test
    @DisplayName("비활성 크레덴셜은 알림 대상이어도 건너뛴다")
    void resolveAlertWebhookUrl_disabled_returnsEmpty() {
        when(repository.findByUserIdAndProviderAndAlertTargetTrue(userId, WebhookProvider.DISCORD))
                .thenReturn(List.of(credential(WebhookProvider.DISCORD, false)));

        assertThat(provider.resolveAlertWebhookUrl(userId)).isEmpty();
    }

    @Test
    @DisplayName("userId가 null이면 조회 없이 빈 Optional")
    void resolveAlertWebhookUrl_nullUserId_returnsEmpty() {
        assertThat(provider.resolveAlertWebhookUrl(null)).isEmpty();
        Mockito.verifyNoInteractions(repository);
    }
}
