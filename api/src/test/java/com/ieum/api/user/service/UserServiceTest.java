package com.ieum.api.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ieum.api.credential.repository.CredentialRepository;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.oauth.service.SlackDisconnectService;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SlackDisconnectService slackDisconnectService;
    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private CredentialRepository credentialRepository;
    @Mock private WebhookCredentialRepository webhookCredentialRepository;
    @Mock private McpServerCatalogRepository mcpServerCatalogRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private ConnectedAccountRepository connectedAccountRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @InjectMocks private UserService userService;

    @Test
    @DisplayName("회원 탈퇴 — users 락 → Slack 해제 → 사용자 데이터 정리 → users 삭제 순서")
    void deleteMyAccountCleansUpAllUserData() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));

        userService.deleteMyAccount(userId);

        InOrder order = inOrder(userRepository, slackDisconnectService, workflowCrudService, credentialRepository,
            webhookCredentialRepository, mcpServerCatalogRepository, promptTemplateRepository,
            connectedAccountRepository, refreshTokenRepository);
        order.verify(userRepository).findByIdForUpdate(userId); // users 행 락을 먼저 — 설치 콜백과 락 순서(users → slack_installations)를 맞춘다
        order.verify(slackDisconnectService).disconnect(userId); // 링크가 남으면 봇 토큰이 영구 잔존
        order.verify(workflowCrudService).deleteAllByUserId(userId);
        order.verify(credentialRepository).deleteByUserId(userId);
        order.verify(webhookCredentialRepository).deleteByUserId(userId);
        order.verify(mcpServerCatalogRepository).deleteByUserId(userId);
        order.verify(promptTemplateRepository).deleteByUserId(userId);
        order.verify(connectedAccountRepository).deleteByUserId(userId);
        order.verify(refreshTokenRepository).deleteById(userId.toString());
        order.verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("없는 사용자면 NOT_FOUND이고 아무것도 지우지 않는다")
    void deleteUnknownUser() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteMyAccount(userId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(slackDisconnectService, workflowCrudService, credentialRepository,
            webhookCredentialRepository, mcpServerCatalogRepository, promptTemplateRepository,
            connectedAccountRepository, refreshTokenRepository);
    }
}
