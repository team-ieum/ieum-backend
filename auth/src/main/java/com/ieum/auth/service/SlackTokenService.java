package com.ieum.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;

/**
 * 사용자 링크의 Slack 설치에서 유효한 봇 토큰(평문)을 내준다 — 실행 경로·드롭다운이 이것만 부른다(spec §5).
 * rotation이 켜진 설치는 만료 5분 안에서 refresh 교환으로 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackTokenService {

    public static final String RECONNECT_MESSAGE = "Slack을 다시 연결해 주세요";

    /** 재연결로만 풀리는 거절(Slack 오류 표). 나머지 ok:false·HTTP 오류는 설정·일시 장애로 본다 */
    private static final Set<String> RECONNECT_ERRORS = Set.of(
        "invalid_refresh_token", "token_revoked", "token_expired", "account_inactive", "invalid_auth");

    private final SlackUserLinkRepository slackUserLinkRepository;
    private final SlackInstallationRepository slackInstallationRepository;
    private final AesEncryptor aesEncryptor;
    private final SlackApiClient slackApiClient;
    private final EntityManager entityManager;

    @Value("${slack.oauth.client-id}")
    private String clientId;

    @Value("${slack.oauth.client-secret}")
    private String clientSecret;

    /** refresh 교환 결과(전부 평문). expiresIn은 초. toString은 토큰 값을 싣지 않는다 */
    public record RefreshedTokens(String accessToken, String refreshToken, long expiresIn) {
        @Override
        public String toString() {
            return "RefreshedTokens[expiresIn=" + expiresIn + "]";
        }
    }

    /**
     * 회전된 refresh는 호출자 롤백과 무관하게 커밋돼야 해 REQUIRES_NEW다 — refresh는 1회용이라 롤백되면 죽은 토큰만 남는다.
     * 설치 행 락을 잡은 트랜잭션에서 부르면 새 트랜잭션이 같은 락을 기다려 교착한다 — 해제는
     * {@link #exchangeRefreshToken}을 쓴다.
     *
     * @throws CustomException SLACK_NOT_CONNECTED · AUTHENTICATION_REQUIRED(재연결) · TOKEN_REFRESH_FAILED
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String getValidBotToken(UUID userId) {
        SlackInstallation installation = slackUserLinkRepository.findByUserId(userId)
            .map(SlackUserLink::getInstallation)
            .orElseThrow(() -> new CustomException(ErrorCode.SLACK_NOT_CONNECTED));
        try {
            if (!installation.needsRefresh(LocalDateTime.now())) { // 첫 호출이 지연 프록시를 초기화한다
                return aesEncryptor.decrypt(installation.getBotToken());
            }
            // 락과 최신 상태를 함께 얻는다 — 같은 설치를 공유하는 다른 요청이 먼저 갱신했으면 그 토큰을 쓴다(refresh 1회용)
            entityManager.refresh(installation, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException e) {
            // 링크 조회 뒤 동시 해제로 설치 행이 지워졌다
            throw new CustomException(ErrorCode.SLACK_NOT_CONNECTED);
        }
        if (!installation.needsRefresh(LocalDateTime.now())) {
            return aesEncryptor.decrypt(installation.getBotToken());
        }
        return rotate(installation);
    }

    /**
     * oauth.v2.access(grant_type=refresh_token) 교환만 하고 저장하지 않는다 — 폼 구성과 거절 분류는 여기 한 곳이다.
     * 해제(SlackDisconnectService)가 쓴다(설치를 지울 거라 회전 결과를 저장할 곳이 없다).
     *
     * @throws CustomException AUTHENTICATION_REQUIRED(재연결) · TOKEN_REFRESH_FAILED
     */
    public RefreshedTokens exchangeRefreshToken(String refreshTokenPlain) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshTokenPlain);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        JsonNode body;
        try {
            body = slackApiClient.call("oauth.v2.access", form, null);
        } catch (RestClientException e) {
            // HTTP 429·5xx·네트워크 — 일시 장애로 분류한다(재시도 여부는 호출자 몫)
            log.warn("[SlackTokenService] 갱신 호출 실패 — {}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
        String accessToken = body.path("access_token").asText("");
        String refreshToken = body.path("refresh_token").asText("");
        if (!body.path("ok").asBoolean(false) || accessToken.isEmpty() || refreshToken.isEmpty()) {
            String error = body.path("error").asText("missing_token");
            log.warn("[SlackTokenService] 갱신 거절 — error: {}", error);
            throw RECONNECT_ERRORS.contains(error)
                ? new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, RECONNECT_MESSAGE)
                : new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
        return new RefreshedTokens(accessToken, refreshToken, body.path("expires_in").asLong(43_200L));
    }

    /** 교환 결과로 새 access·refresh·만료를 저장하고 새 access(평문)를 돌려준다. 만료 전 일시 장애면 저장 access를 준다 */
    private String rotate(SlackInstallation installation) {
        if (installation.getRefreshToken() == null) {
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, RECONNECT_MESSAGE);
        }
        RefreshedTokens tokens;
        try {
            tokens = exchangeRefreshToken(aesEncryptor.decrypt(installation.getRefreshToken()));
        } catch (CustomException e) {
            // 일시 장애인데 저장 토큰이 아직 살아 있으면 그걸 쓴다(spec §5-5). 거절·만료 후는 그대로 던진다
            if (e.getErrorCode() != ErrorCode.TOKEN_REFRESH_FAILED
                || !LocalDateTime.now().isBefore(installation.getTokenExpiresAt())) {
                throw e;
            }
            log.warn("[SlackTokenService] 갱신 실패 — 만료 전 저장 토큰 사용, team: {}", installation.getTeamId());
            return aesEncryptor.decrypt(installation.getBotToken());
        }
        installation.rotate(aesEncryptor.encrypt(tokens.accessToken()), aesEncryptor.encrypt(tokens.refreshToken()),
            LocalDateTime.now().plusSeconds(tokens.expiresIn()));
        slackInstallationRepository.save(installation); // refresh는 1회용 — 반드시 덮어쓴다
        log.info("[SlackTokenService] 봇 토큰 갱신 — team: {}", installation.getTeamId());
        return tokens.accessToken();
    }
}
