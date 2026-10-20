package com.ieum.api.oauth.service;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OAuthConnectionService {

    private final ConnectedAccountRepository connectedAccountRepository;

    /**
     * 사용자의 연동된 OAuth 계정 목록을 조회한다.
     *
     * @param userId 현재 인증된 사용자 ID
     * @return OAuth 연동 목록 응답 DTO 리스트
     */
    public List<OAuthConnectionResponse> getConnections(UUID userId) {
        log.debug("[OAuthConnectionService] OAuth 연동 목록 조회 — userId: {}", userId);
        return connectedAccountRepository.findByUserId(userId).stream()
            .map(OAuthConnectionResponse::from)
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

        connectedAccountRepository.delete(account);
        log.info("[OAuthConnectionService] OAuth 연동 해제 — userId: {}, provider: {}", userId, provider);
    }
}
