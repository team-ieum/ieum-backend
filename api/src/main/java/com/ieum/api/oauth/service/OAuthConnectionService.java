package com.ieum.api.oauth.service;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.repository.ConnectedAccountRepository;
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
}
