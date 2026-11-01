package com.ieum.api.oauth.service;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OAuthConnectionService {

    private final ConnectedAccountRepository connectedAccountRepository;
    private final AesEncryptor aesEncryptor;
    private final OAuthTokenRevoker tokenRevoker;
    private final SlackUserLinkRepository slackUserLinkRepository;
    private final DiscordGuildLinkRepository discordGuildLinkRepository;

    /**
     * 사용자의 연동된 OAuth 계정 목록을 조회한다.
     *
     * @param userId 현재 인증된 사용자 ID
     * @return OAuth 연동 목록 응답 DTO 리스트
     */
    public List<OAuthConnectionResponse> getConnections(UUID userId) {
        log.debug("[OAuthConnectionService] OAuth 연동 목록 조회 — userId: {}", userId);
        return Stream.of(
                connectedAccountRepository.findByUserId(userId).stream().map(OAuthConnectionResponse::from),
                slackUserLinkRepository.findByUserId(userId).map(OAuthConnectionResponse::fromSlack).stream(),
                discordGuildLinkRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                    .map(OAuthConnectionResponse::fromDiscord))
            .flatMap(s -> s)
            .toList();
    }

    /**
     * OAuth 연동을 해제한다 — 우리 DB의 토큰을 즉시 하드 삭제한다(소프트 딜리트 금지).
     *
     * <p>로그인 신원은 {@code users.provider_id}로 찾으므로 Google 로그인 사용자가 Google 연동을 해제해도
     * 로그인은 계속 된다. 해제 후 그 서비스를 쓰는 노드는 실행 시 {@code ACCOUNT_NOT_CONNECTED}로 실패한다.
     */
    @Transactional
    public void disconnect(UUID userId, AuthProvider provider) {
        if (provider == AuthProvider.LOCAL) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        ConnectedAccount account = connectedAccountRepository.findByUserIdAndProvider(userId, provider)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));

        // 행을 지우면 토큰을 잃으므로 먼저 복호화해 둔다. 외부 폐기는 커밋 후 best-effort —
        // 외부 장애로 해제가 롤백되면 사용자가 토큰을 지울 방법이 없어진다.
        String accessToken = decryptOrNull(account.getAccessToken());
        String refreshToken = decryptOrNull(account.getRefreshToken());
        connectedAccountRepository.delete(account);
        log.info("[OAuthConnectionService] OAuth 연동 해제 — userId: {}, provider: {}", userId, provider);

        // ponytail: 커밋 후 동기 호출이라 외부 응답이 늦으면 해제 응답도 그만큼(최대 read timeout 30초) 늦어진다. 문제가 되면 @Async로
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tokenRevoker.revoke(provider, accessToken, refreshToken);
            }
        });
    }

    /** 복호화 실패(키 교체 등)는 외부 폐기만 건너뛰고 해제는 진행한다. */
    private String decryptOrNull(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            return aesEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("[OAuthConnectionService] 토큰 복호화 실패 — 외부 폐기 생략: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
