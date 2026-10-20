package com.ieum.api.oauth.service;

import com.ieum.auth.domain.SlackOAuthState;
import com.ieum.auth.repository.SlackOAuthStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Slack 설치(OAuth v2, 봇 토큰) 연결 — Notion 연결 모양에 브라우저 nonce(spec S1)를 더했다.
 * 클래스에 @Transactional을 달지 않는다 — Slack HTTP 호출 동안 DB 트랜잭션을 잡지 않고 저장만 짧게 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackOAuthService {

    public static final long STATE_TTL_SECONDS = 300L;
    private static final String AUTHORIZE_URL = "https://slack.com/oauth/v2/authorize";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SlackOAuthStateRepository slackOAuthStateRepository;

    @Value("${slack.oauth.client-id}")
    private String clientId;

    @Value("${slack.oauth.redirect-uri}")
    private String redirectUri;

    @Value("${slack.oauth.bot-scopes}")
    private String botScopes;

    /** @param nonce 브라우저 쿠키로 줄 원문. state에는 해시만 남는다 */
    public record Authorization(String authUrl, String nonce) {
    }

    public Authorization authorize(UUID userId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String state = UUID.randomUUID().toString();
        slackOAuthStateRepository.save(SlackOAuthState.builder()
            .state(state)
            .userId(userId)
            .nonceHash(sha256Hex(nonce))
            .ttl(STATE_TTL_SECONDS)
            .build());

        // 봇 scope만 — user_scope는 넣지 않는다(사용자 토큰 제외, D4)
        String authUrl = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
            .queryParam("client_id", clientId)
            .queryParam("scope", botScopes)
            .queryParam("state", state)
            .queryParam("redirect_uri", redirectUri)
            .encode()
            .build()
            .toUriString();
        return new Authorization(authUrl, nonce);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e);
        }
    }
}
