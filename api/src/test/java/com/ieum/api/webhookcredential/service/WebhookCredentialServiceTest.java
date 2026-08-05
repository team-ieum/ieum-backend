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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

class WebhookCredentialServiceTest {

    private WebhookCredentialRepository repository;
    private AesEncryptionService aes;
    private WebhookCredentialService service;

    private final UUID userId = UUID.randomUUID();
    private static final String URL = "https://discord.com/api/webhooks/123/abcDEF-secret";
    private static final String SLACK_URL = "https://hooks.slack.com/services/T000/B000/abcDEF-secret";

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(WebhookCredentialRepository.class);
        aes = new AesEncryptionService("01234567890123456789012345678901"); // 32 bytes
        service = new WebhookCredentialService(repository, aes);
    }

    @Test
    @DisplayName("성공 - 웹훅 URL 암호화 저장")
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
    @DisplayName("중복 이름 - 예외 발생")
    void create_duplicateName_throws() {
        when(repository.existsByUserIdAndDisplayName(userId, "중복")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                userId, WebhookProvider.SLACK, "중복", SLACK_URL, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_DUPLICATE_NAME);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("등록 한도 초과 - 예외 발생")
    void create_limitExceeded_throws() {
        when(repository.existsByUserIdAndDisplayName(userId, "초과")).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(
                userId, WebhookProvider.SLACK, "초과", SLACK_URL, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_LIMIT_EXCEEDED);

        verify(repository, never()).save(any());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
            // provider와 다른 서비스의 웹훅
            "SLACK, https://discord.com/api/webhooks/123/abcdef",
            "DISCORD, https://hooks.slack.com/services/T0/B0/abcdef",
            // 임의 도메인
            "SLACK, https://evil.example.com/hook/abcdef",
            "DISCORD, https://evil.example.com/api/webhooks/1/2",
            // 호스트를 흉내 낸 도메인
            "SLACK, https://hooks.slack.com.evil.example/services/T0/B0/x",
            "DISCORD, https://discord.com.evil.example/api/webhooks/1/2",
            // 진짜 웹훅 URL을 쿼리에 끼운 우회 (실제 호출 대상은 evil.example)
            "SLACK, https://evil.example/?u=https://hooks.slack.com/services/T0/B0/x",
            // 평문 http
            "SLACK, http://hooks.slack.com/services/T0/B0/x",
            "DISCORD, http://discord.com/api/webhooks/1/2",
            // 토큰 구간 없는 호스트만
            "SLACK, https://hooks.slack.com/services/",
            "DISCORD, https://discord.com/api/webhooks",
            // 웹훅이 아닌 같은 회사 URL
            "SLACK, https://api.slack.com/methods/chat.postMessage",
            "DISCORD, https://discord.com/api/v10/users/@me"
    })
    @DisplayName("provider와 맞지 않는 URL은 등록 거부 - 저장까지 가지 않는다")
    void create_urlNotMatchingProvider_throws(WebhookProvider provider, String webhookUrl) {
        assertThatThrownBy(() -> service.create(userId, provider, "이름", webhookUrl, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_INVALID_URL);

        verify(repository, never()).save(any());
        // 중복·한도 조회보다 먼저 걸러진다
        verify(repository, never()).existsByUserIdAndDisplayName(any(), any());
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @CsvSource({
            "SLACK, https://hooks.slack.com/services/T00000000/B00000000/aBcDeF123456",
            // Slack 워크플로우 트리거 웹훅
            "SLACK, https://hooks.slack.com/triggers/T00000000/123456/aBcDeF123456",
            "DISCORD, https://discord.com/api/webhooks/1234567890/abcdefghij",
            // 구 도메인·서브도메인·버전 낀 경로 (Discord가 실제로 쓰는 형태)
            "DISCORD, https://discordapp.com/api/webhooks/1234567890/abcdefghij",
            "DISCORD, https://canary.discord.com/api/webhooks/1234567890/abcdefghij",
            "DISCORD, https://discord.com/api/v10/webhooks/1234567890/abcdefghij"
    })
    @DisplayName("provider에 맞는 웹훅 URL은 등록된다")
    void create_urlMatchingProvider_succeeds(WebhookProvider provider, String webhookUrl) {
        when(repository.existsByUserIdAndDisplayName(any(), any())).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(0L);
        when(repository.save(any(WebhookCredential.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookCredential result = service.create(userId, provider, "이름", webhookUrl, null);

        assertThat(service.decryptWebhookUrl(result)).isEqualTo(webhookUrl);
    }

    @Test
    @DisplayName("앞뒤 공백은 떼고 검증·저장한다")
    void create_urlWithSurroundingWhitespace_isTrimmed() {
        when(repository.existsByUserIdAndDisplayName(any(), any())).thenReturn(false);
        when(repository.countByUserId(userId)).thenReturn(0L);
        when(repository.save(any(WebhookCredential.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookCredential result = service.create(
                userId, WebhookProvider.SLACK, "이름", "  " + SLACK_URL + "\n", null);

        assertThat(service.decryptWebhookUrl(result)).isEqualTo(SLACK_URL);
    }

    @Test
    @DisplayName("거부 메시지에 사용자가 보낸 URL이 들어가지 않는다")
    void create_rejection_doesNotLeakUrl() {
        String url = "https://evil.example.com/hook/superSecretToken";

        assertThatThrownBy(() -> service.create(userId, WebhookProvider.SLACK, "이름", url, null))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining("superSecretToken")
                .hasMessageNotContaining("evil.example.com");
    }

    @Test
    @DisplayName("이미 등록된 크레덴셜은 형식이 달라도 조회·사용·삭제가 그대로 된다")
    void existingCredential_withNonConformingUrl_isNotRevalidated() {
        UUID id = UUID.randomUUID();
        String legacyUrl = "https://legacy.example.com/hook/oldToken";
        WebhookCredential stored = WebhookCredential.builder()
                .userId(userId).provider(WebhookProvider.SLACK).displayName("옛날 등록분")
                .encryptedWebhookUrl(aes.encrypt(legacyUrl)).enabled(true).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(stored));
        when(repository.findByUserId(userId)).thenReturn(List.of(stored));

        assertThat(service.getByIdAndUserId(id, userId)).isSameAs(stored);
        assertThat(service.getByUserId(userId)).containsExactly(stored);
        assertThat(service.decryptWebhookUrl(stored)).isEqualTo(legacyUrl);

        service.delete(id, userId);
        verify(repository).delete(stored);
    }

    @Test
    @DisplayName("조회 없음 - 예외 발생")
    void getByIdAndUserId_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByIdAndUserId(id, userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_NOT_FOUND);
    }

    private WebhookCredential credential(WebhookProvider provider, boolean alertTarget) {
        WebhookCredential credential = WebhookCredential.builder()
                .userId(userId).provider(provider).displayName("채널-" + provider)
                .encryptedWebhookUrl(aes.encrypt(URL)).enabled(true).build();
        credential.changeAlertTarget(alertTarget);
        return credential;
    }

    @Test
    @DisplayName("알림 대상 지정 - 기존 DISCORD 대상은 해제되고 새 대상만 남는다")
    void setAlertTarget_replacesExistingTarget() {
        UUID newId = UUID.randomUUID();
        WebhookCredential existing = credential(WebhookProvider.DISCORD, true);
        WebhookCredential target = credential(WebhookProvider.DISCORD, false);
        when(repository.findByIdAndUserId(newId, userId)).thenReturn(Optional.of(target));
        when(repository.findByUserIdAndProviderAndAlertTargetTrue(userId, WebhookProvider.DISCORD))
                .thenReturn(List.of(existing));

        WebhookCredential result = service.setAlertTarget(newId, userId, true);

        assertThat(result.isAlertTarget()).isTrue();
        assertThat(existing.isAlertTarget()).isFalse();
    }

    @Test
    @DisplayName("알림 대상 해제 - 기존 대상 조회 없이 해당 크레덴셜만 내린다")
    void setAlertTarget_false_clearsOnlyThisCredential() {
        UUID id = UUID.randomUUID();
        WebhookCredential target = credential(WebhookProvider.DISCORD, true);
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(target));

        WebhookCredential result = service.setAlertTarget(id, userId, false);

        assertThat(result.isAlertTarget()).isFalse();
        verify(repository, never())
                .findByUserIdAndProviderAndAlertTargetTrue(any(), any());
    }

    @Test
    @DisplayName("DISCORD가 아닌 크레덴셜은 알림 대상으로 지정할 수 없다")
    void setAlertTarget_nonDiscord_throws() {
        UUID id = UUID.randomUUID();
        WebhookCredential slack = credential(WebhookProvider.SLACK, false);
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(slack));

        assertThatThrownBy(() -> service.setAlertTarget(id, userId, true))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_NOT_ALERTABLE);

        assertThat(slack.isAlertTarget()).isFalse();
    }

    @Test
    @DisplayName("남의 크레덴셜은 알림 대상으로 지정할 수 없다")
    void setAlertTarget_otherUsersCredential_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setAlertTarget(id, userId, true))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEBHOOK_CREDENTIAL_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제 - 리포지토리 정상 호출")
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
