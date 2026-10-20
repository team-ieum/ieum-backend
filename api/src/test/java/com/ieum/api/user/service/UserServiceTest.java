package com.ieum.api.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.ieum.api.credential.repository.CredentialRepository;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private CredentialRepository credentialRepository;
    @Mock private WebhookCredentialRepository webhookCredentialRepository;
    @Mock private McpServerCatalogRepository mcpServerCatalogRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private ConnectedAccountRepository connectedAccountRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @InjectMocks private UserService userService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void 탈퇴하면_사용자_데이터를_모두_지우고_users는_마지막에_지운다() {
        User user = User.builder().email("a@b.com").name("a").provider(AuthProvider.LOCAL).role(UserRole.ROLE_USER).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        userService.deleteMyAccount(userId);

        InOrder order = inOrder(workflowCrudService, credentialRepository, webhookCredentialRepository,
            mcpServerCatalogRepository, promptTemplateRepository, connectedAccountRepository,
            refreshTokenRepository, userRepository);
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
    void 없는_사용자면_아무것도_지우지_않는다() {
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteMyAccount(userId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        then(workflowCrudService).should(never()).deleteAllByUserId(any());
        then(credentialRepository).should(never()).deleteByUserId(any());
        then(refreshTokenRepository).should(never()).deleteById(any());
    }
}
