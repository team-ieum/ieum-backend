package com.ieum.api.oauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackOAuthState;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackOAuthStateRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.auth.service.SlackApiClient;
import com.ieum.common.util.AesEncryptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
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
    private final SlackInstallationRepository slackInstallationRepository;
    private final SlackUserLinkRepository slackUserLinkRepository;
    private final UserRepository userRepository;
    private final SlackApiClient slackApiClient;
    private final AesEncryptor aesEncryptor;
    private final TransactionTemplate transactionTemplate;

    @Value("${slack.oauth.client-secret}")
    private String clientSecret;

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

    /**
     * 설치 콜백 판정(spec §4-2 — 순서를 바꾸지 않는다). 돌려주는 값은 프런트 리다이렉트 쿼리다.
     * 응답 team이 진실이다(authorize의 team 힌트가 아니라). 로그엔 응답 본문을 남기지 않는다 — 토큰이 들어 있다.
     */
    public String handleCallback(String state, String nonce, String code, String error) {
        // 2-1 state 조회·즉시 삭제(1회 소비 — 쿠키가 틀려도 다시 못 쓴다)
        SlackOAuthState saved = state == null ? null : slackOAuthStateRepository.findById(state).orElse(null);
        if (saved == null) {
            return "error=slack_state_expired";
        }
        slackOAuthStateRepository.delete(saved);

        // 2-2 이 브라우저가 설치를 시작했는가(S1) — 아니면 교환하지 않는다
        if (nonce == null || !MessageDigest.isEqual(sha256Hex(nonce).getBytes(StandardCharsets.UTF_8),
                saved.getNonceHash().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[SlackOAuthService] nonce 불일치 — userId: {}", saved.getUserId());
            return "error=slack_state_mismatch";
        }

        // 2-3 취소·오류
        if (error != null || code == null || code.isBlank()) {
            log.info("[SlackOAuthService] 설치 취소·오류 — error: {}", error);
            return "error=slack_denied";
        }

        // 2-4 코드 교환
        JsonNode body;
        try {
            body = slackApiClient.call("oauth.v2.access", exchangeForm(code), null);
        } catch (RestClientException e) {
            log.warn("[SlackOAuthService] 코드 교환 호출 실패 — {}", e.getClass().getSimpleName());
            return "error=slack_exchange";
        }
        String accessToken = body.path("access_token").asText("");
        if (!body.path("ok").asBoolean(false) || accessToken.isEmpty()) {
            log.warn("[SlackOAuthService] 코드 교환 거절 — error: {}", body.path("error").asText());
            return "error=slack_exchange";
        }

        // 2-5 조직 설치·봇 아닌 토큰 거절
        String teamId = body.path("team").path("id").asText("");
        boolean enterpriseInstall = body.path("is_enterprise_install").asBoolean(false);
        String tokenType = body.path("token_type").asText("");
        log.info("[SlackOAuthService] 설치 응답 — team: {}, is_enterprise_install: {}, token_type: {}",
            teamId, enterpriseInstall, tokenType);
        if (enterpriseInstall || teamId.isEmpty() || !"bot".equals(tokenType)) {
            slackApiClient.revokeQuietly(accessToken);
            return "error=slack_org_install";
        }

        // 2-6~2-8 저장 — Slack 호출이 끝난 뒤에만 짧은 트랜잭션
        Outcome outcome;
        try {
            try {
                outcome = transactionTemplate.execute(tx -> save(saved.getUserId(), teamId, body, accessToken));
            } catch (DataIntegrityViolationException e) {
                // 같은 팀 첫 설치가 동시에 들어와 team_id 유일 제약에 걸렸다 — 다시 돌면 먼저 들어간 행을 락으로 잡는다
                outcome = transactionTemplate.execute(tx -> save(saved.getUserId(), teamId, body, accessToken));
            }
        } catch (RuntimeException e) {
            // 재시도 실패·동시 해제(StaleState)·락·암호화 실패 — 브라우저 최상위 이동에 JSON 500을 띄우지 않는다.
            // revoke는 하지 않는다: 같은 팀 재설치면 받은 토큰이 공유 설치의 봇 토큰과 같을 수 있다(spec §17)
            log.warn("[SlackOAuthService] 설치 저장 실패 — {}", e.getClass().getSimpleName());
            return "error=slack_exchange";
        }
        if (outcome == Outcome.OTHER_TEAM_UNUSED || outcome == Outcome.USER_GONE_UNUSED) {
            slackApiClient.revokeQuietly(accessToken); // 커밋 뒤 — 아무도 안 쓰는 설치를 남기지 않는다
        }
        return switch (outcome) {
            case CONNECTED -> "connected=slack";
            case OTHER_TEAM_KEPT, OTHER_TEAM_UNUSED -> "error=slack_other_team";
            case USER_GONE_KEPT, USER_GONE_UNUSED -> "error=slack_state_expired";
        };
    }

    private enum Outcome { CONNECTED, OTHER_TEAM_KEPT, OTHER_TEAM_UNUSED, USER_GONE_KEPT, USER_GONE_UNUSED }

    private Outcome save(UUID userId, String teamId, JsonNode body, String accessToken) {
        // state를 만든 사용자가 그새 탈퇴했다 — 주인 없는 링크·설치를 만들지 않는다. 그 팀 설치가 있으면 토큰이 공유 봇 토큰일 수 있어 revoke하지 않는다
        if (!userRepository.existsById(userId)) {
            return slackInstallationRepository.findByTeamIdForUpdate(teamId).isPresent()
                ? Outcome.USER_GONE_KEPT : Outcome.USER_GONE_UNUSED;
        }
        Optional<SlackUserLink> link = slackUserLinkRepository.findByUserId(userId);
        Optional<SlackInstallation> locked = slackInstallationRepository.findByTeamIdForUpdate(teamId);

        // 2-6 다른 팀에 이미 연결돼 있으면 링크를 옮기지 않는다(D8 — 먼저 해제). 받은 토큰은 그 팀 설치가 있으면 반영한다
        if (link.isPresent() && !link.get().getInstallation().getTeamId().equals(teamId)) {
            locked.ifPresent(installation -> apply(installation, body, accessToken));
            return locked.isPresent() ? Outcome.OTHER_TEAM_KEPT : Outcome.OTHER_TEAM_UNUSED;
        }

        // 2-7 설치 upsert(행 락) — 2-8 링크 upsert
        SlackInstallation installation = locked.orElseGet(() -> new SlackInstallation(teamId));
        apply(installation, body, accessToken);
        slackInstallationRepository.saveAndFlush(installation);
        String slackUserId = body.path("authed_user").path("id").asText(null);
        link.ifPresentOrElse(
            existing -> existing.updateSlackUserId(slackUserId),
            () -> slackUserLinkRepository.saveAndFlush(new SlackUserLink(userId, installation, slackUserId)));
        return Outcome.CONNECTED;
    }

    /** 설치 응답을 덮어쓴다 — refresh_token·expires_in이 없으면(rotation 꺼짐) null로 둔다 */
    private void apply(SlackInstallation installation, JsonNode body, String accessToken) {
        String refreshToken = body.path("refresh_token").asText("");
        LocalDateTime expiresAt = body.hasNonNull("expires_in")
            ? LocalDateTime.now().plusSeconds(body.get("expires_in").asLong())
            : null;
        installation.applyInstall(
            aesEncryptor.encrypt(accessToken),
            refreshToken.isEmpty() ? null : aesEncryptor.encrypt(refreshToken),
            expiresAt,
            body.path("scope").asText(null),
            body.path("team").path("name").asText(null),
            body.path("bot_user_id").asText(null),
            body.path("app_id").asText(null));
    }

    private MultiValueMap<String, String> exchangeForm(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        return form;
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
