package com.ieum.api.oauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.domain.DiscordOAuthState;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.repository.DiscordOAuthStateRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.auth.service.DiscordApiClient;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
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
    /** Discord snowflake — ASCII 숫자만, 공백·경로 문자 불가(matches()라 끝 개행도 불가) */
    private static final Pattern SNOWFLAKE = Pattern.compile("^[0-9]{17,20}$");

    private final DiscordOAuthStateRepository stateRepository;
    private final DiscordGuildLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final DiscordApiClient discordApiClient;
    private final DiscordDisconnectService discordDisconnectService;
    private final TransactionTemplate transactionTemplate;

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

    /**
     * 설치 콜백 판정(spec §4-2 — 순서를 바꾸지 않는다). 돌려주는 값은 프런트 리다이렉트 쿼리다.
     * 교환 응답 guild가 진실이다 — 쿼리의 guild_id는 힌트라 교환 응답이 끊긴 실패의 퇴장 판정 밖에서는 쓰지 않는다(spec §4-2-4).
     * 로그엔 응답 본문을 남기지 않는다(토큰 포함).
     */
    public String handleCallback(String state, String nonce, String code, String error, String guildIdHint) {
        // 2-1 state 조회·즉시 삭제(1회 소비 — 쿠키가 틀려도 다시 못 쓴다)
        DiscordOAuthState saved = state == null ? null : stateRepository.findById(state).orElse(null);
        if (saved == null) {
            return "error=discord_state_expired";
        }
        stateRepository.delete(saved);

        // 2-2 이 브라우저가 설치를 시작했는가(D8) — 아니면 교환하지 않는다(교환하지 않으면 봇도 들어오지 않는다)
        if (nonce == null || !MessageDigest.isEqual(sha256Hex(nonce).getBytes(StandardCharsets.UTF_8),
                saved.getNonceHash().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[DiscordOAuthService] nonce 불일치 — userId: {}", saved.getUserId());
            return "error=discord_state_mismatch";
        }

        // 2-3 취소·오류
        if (error != null || code == null || code.isBlank()) {
            log.info("[DiscordOAuthService] 설치 취소·오류 — error: {}", error);
            return "error=discord_denied";
        }

        // 2-4 코드 교환 — 이 시점에 봇이 서버에 들어온다(bot_require_code_grant)
        JsonNode body;
        try {
            body = discordApiClient.exchangeCode(code, redirectUri);
        } catch (RestClientException e) {
            // spec §4-2-4 — 타임아웃·5xx는 Discord가 교환을 처리해 봇이 이미 들어왔을 수 있다. 4xx·429 소진은 처리되지 않았다
            boolean maybeProcessed = e instanceof ResourceAccessException || e instanceof HttpServerErrorException;
            boolean leaveCheck = maybeProcessed && guildIdHint != null && SNOWFLAKE.matcher(guildIdHint).matches();
            log.warn("[DiscordOAuthService] 코드 교환 실패 — {}, 힌트 퇴장 판정: {}", DiscordApiClient.describe(e), leaveCheck);
            if (leaveCheck) {
                discordDisconnectService.leaveIfOrphan(guildIdHint);
            }
            return "error=discord_exchange";
        }
        String accessToken = body.path("access_token").asText("");

        // 2-5 설치된 서버가 응답에 없으면 연결할 대상이 없다
        String guildId = body.path("guild").path("id").asText("");
        if (guildId.isEmpty()) {
            log.warn("[DiscordOAuthService] 교환 응답에 guild 없음");
            discordApiClient.revokeQuietly(accessToken);
            return "error=discord_exchange";
        }
        String guildName = body.path("guild").path("name").asText(null);
        log.info("[DiscordOAuthService] 설치 응답 — guild: {}", guildId);

        // 2-6 설치한 Discord 사용자(실패해도 진행)
        String discordUserId = null;
        try {
            discordUserId = discordApiClient.currentUser(accessToken).path("id").asText(null);
        } catch (RestClientException e) {
            log.warn("[DiscordOAuthService] /users/@me 실패 — guild: {}, {}", guildId, DiscordApiClient.describe(e));
        }

        // 2-7 사용자 토큰은 쓰지 않는다 — 바로 폐기(best-effort)
        discordApiClient.revokeQuietly(accessToken);

        // 2-8 링크 upsert — Discord 호출이 끝난 뒤에만 짧은 트랜잭션
        UUID userId = saved.getUserId();
        String installer = discordUserId;
        Outcome outcome;
        try {
            outcome = transactionTemplate.execute(tx -> save(userId, guildId, guildName, installer));
        } catch (RuntimeException e) {
            // 2-9 봇은 이미 서버에 있다 — 링크 없는 봇이 영원히 남지 않게 퇴장 판정
            log.warn("[DiscordOAuthService] 링크 저장 실패 — guild: {}, {}", guildId, e.getClass().getSimpleName());
            discordDisconnectService.leaveIfOrphan(guildId);
            return "error=discord_exchange";
        }
        if (outcome == Outcome.USER_GONE) {
            // 2-9 authorize 뒤 5분 안에 탈퇴(Slack USER_GONE 선례) — 링크를 만들지 않고 퇴장 판정
            discordDisconnectService.leaveIfOrphan(guildId);
            return "error=discord_state_expired";
        }
        return "connected=discord";
    }

    private enum Outcome { CONNECTED, USER_GONE }

    private Outcome save(UUID userId, String guildId, String guildName, String discordUserId) {
        // users 행 락 — 탈퇴(UserService.deleteMyAccount)와 같은 순서(users → 링크)로 잡아 탈퇴와 엇갈린 고아 링크를 막는다
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return Outcome.USER_GONE;
        }
        linkRepository.findByUserIdAndGuildId(userId, guildId).ifPresentOrElse(
            link -> link.refresh(guildName, discordUserId),
            () -> linkRepository.saveAndFlush(new DiscordGuildLink(userId, guildId, guildName, discordUserId)));
        return Outcome.CONNECTED;
    }

    /** Slack 것을 부르지 않고 복제한다 — 앱끼리 코드로 묶지 않는다(spec §1) */
    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK 구현체가 필수 제공 — 발생하지 않는다
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
