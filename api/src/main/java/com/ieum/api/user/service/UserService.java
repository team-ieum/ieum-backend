package com.ieum.api.user.service;

import com.ieum.api.credential.repository.CredentialRepository;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.oauth.service.DiscordDisconnectService;
import com.ieum.api.oauth.service.SlackDisconnectService;
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
    private final SlackDisconnectService slackDisconnectService;
    private final DiscordDisconnectService discordDisconnectService;
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
     * Slack 연결도 해제한다 — 같은 워크스페이스를 쓰는 다른 사용자가 없으면 커밋 뒤 봇 토큰을 폐기한다.
     * Discord 링크도 전부 지운다 — 그 서버의 마지막 IEUM 링크였으면 커밋 뒤 봇이 서버를 나간다.
     * 이미 발급된 Access Token은 만료(30분)까지 유효하다 — FE가 탈퇴 직후 토큰을 버린다.
     */
    @Transactional
    public void deleteMyAccount(UUID userId) {
        // users 행 락을 해제보다 먼저 — 설치 콜백(SlackOAuthService.save)과 같은 락 순서(users → slack_installations)로 교착·고아 설치를 막는다
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        // Slack 링크가 남으면 "남은 링크 0"에 못 닿아 봇 토큰이 영구 잔존한다(spec §3). revoke는 이 트랜잭션 커밋 뒤에 나간다
        slackDisconnectService.disconnect(userId);
        // Discord도 같다 — 링크가 남으면 아무도 안 쓰는 봇이 서버에 영구 잔존한다. 퇴장 판정·호출은 커밋 뒤(Discord spec §6)
        discordDisconnectService.disconnectAll(userId);
        // Slack·Discord 링크 삭제를 먼저 반영한다 — 아래 bulk DELETE(clearAutomatically)가 flush 전 삭제를 버리면 행이 남는다
        userRepository.flush();

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
