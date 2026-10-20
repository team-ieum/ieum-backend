package com.ieum.api.user.service;

import com.ieum.api.credential.repository.CredentialRepository;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.api.user.dto.UserResponse;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final WorkflowCrudService workflowCrudService;
    private final CredentialRepository credentialRepository;
    private final WebhookCredentialRepository webhookCredentialRepository;
    private final McpServerCatalogRepository mcpServerCatalogRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public UserResponse getMyProfile(UUID userId) {
        User user = findUserById(userId);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateMyProfile(UUID userId, String name) {
        User user = findUserById(userId);
        user.updateName(name);
        return UserResponse.from(user);
    }

    /**
     * 회원 탈퇴 — 사용자 데이터를 한 트랜잭션에서 하드 삭제한다. 중간에 DB 오류가 나면 users 행까지 롤백된다.
     *
     * <p>워크플로우 삭제가 실행 이력·채팅·스케줄(커밋 후)·Mongo 정의(커밋 후)까지 정리한다.
     * 이미 발급된 Access Token은 만료(30분)까지 유효하다 — FE가 탈퇴 직후 토큰을 버린다.
     */
    @Transactional
    public void deleteMyAccount(UUID userId) {
        User user = findUserById(userId);

        workflowCrudService.deleteAllByUserId(userId);
        credentialRepository.deleteByUserId(userId);
        webhookCredentialRepository.deleteByUserId(userId);
        mcpServerCatalogRepository.deleteByUserId(userId);
        promptTemplateRepository.deleteByUserId(userId);
        connectedAccountRepository.deleteByUserId(userId);
        // Redis라 롤백되지 않지만, 롤백돼도 다시 로그인하면 되는 정도라 무해하다.
        refreshTokenRepository.deleteById(userId.toString());

        userRepository.delete(user);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }
}
