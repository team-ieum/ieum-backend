package com.ieum.api.oauth.service;

import com.ieum.auth.domain.DiscordOAuthState;
import com.ieum.auth.repository.DiscordOAuthStateRepository;
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
 * Discord 봇 설치(OAuth2 code grant) 연결 — SlackOAuthService 모양 복제(앱 독립, 공용 추상화 없음).
 * 클래스에 @Transactional을 달지 않는다 — Discord HTTP 호출 동안 DB 트랜잭션을 잡지 않고 저장만 짧게 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordOAuthService {

    public static final long STATE_TTL_SECONDS = 300L;
    private static final String AUTHORIZE_URL = "https://discord.com/oauth2/authorize";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiscordOAuthStateRepository stateRepository;

    @Value("${discord.oauth.client-id}")
    private String clientId;

    @Value("${discord.oauth.redirect-uri}")
    private String redirectUri;

    /** D6 권한 합 — 십진 문자열 그대로 싣는다(계산하지 않는다) */
    @Value("${discord.oauth.permissions}")
    private String permissions;

    /** @param nonce 브라우저 쿠키로 줄 원문. state에는 해시만 남는다 */
    public record Authorization(String authUrl, String nonce) {
    }

    public Authorization authorize(UUID userId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String state = UUID.randomUUID().toString();
        stateRepository.save(DiscordOAuthState.builder()
            .state(state)
            .userId(userId)
            .nonceHash(sha256Hex(nonce))
            .ttl(STATE_TTL_SECONDS)
            .build());

        // bot = 봇 설치, identify = 설치한 Discord 사용자 ID. 앱 설정 Requires OAuth2 Code Grant가 켜져 있어야
        // 봇이 code 교환이 끝날 때에만 서버에 들어오고, 교환 응답에 guild가 담긴다(spec §4-1)
        String authUrl = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
            .queryParam("client_id", clientId)
            .queryParam("scope", "bot identify")
            .queryParam("permissions", permissions)
            .queryParam("response_type", "code")
            .queryParam("redirect_uri", redirectUri)
            .queryParam("state", state)
            .queryParam("integration_type", "0")
            .encode()
            .build()
            .toUriString();
        return new Authorization(authUrl, nonce);
    }

    /** Slack 것을 부르지 않고 복제한다 — 앱끼리 코드로 묶지 않는다(spec §1) */
    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK 구현체가 필수 제공 — 발생하지 않는다
            throw new IllegalStateException("SHA-256 없음", e);
        }
    }
}
