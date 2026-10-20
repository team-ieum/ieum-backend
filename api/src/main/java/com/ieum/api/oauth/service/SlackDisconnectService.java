package com.ieum.api.oauth.service;

import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.auth.service.SlackApiClient;
import com.ieum.auth.service.SlackTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.util.AesEncryptor;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Slack 연결 해제(spec §6). 공유 설치를 남이 지우지 못하게 내 링크만 지우고, 마지막 사용자일 때만 설치를 지우고 봇 토큰을 폐기한다.
 * 폐기는 봇 비활성화(채널 멤버십 제거)이지 앱 제거가 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackDisconnectService {

    private final SlackUserLinkRepository slackUserLinkRepository;
    private final SlackInstallationRepository slackInstallationRepository;
    private final SlackTokenService slackTokenService;
    private final SlackApiClient slackApiClient;
    private final AesEncryptor aesEncryptor;

    /** 커밋 뒤 revoke에 쓸 평문. refresh는 rotation 꺼져 있으면 null */
    private record RevokeTarget(String teamId, String botToken, String refreshToken, boolean expiring) {
        @Override
        public String toString() {
            return "RevokeTarget[teamId=" + teamId + ", expiring=" + expiring + "]";
        }
    }

    /**
     * 링크가 없으면 아무것도 안 한다(멱등). Slack 호출(refresh 교환·revoke)은 전부 트랜잭션 커밋 뒤다 — 회원 탈퇴 트랜잭션에
     * 합류하면 탈퇴가 커밋된 뒤라, 롤백되면 1회용 refresh도 소비되지 않는다. 락을 쥔 채 Slack HTTP를 기다리지도 않는다.
     * 설치 행 락은 콜백 upsert·토큰 갱신과 같은 행이라 서로 직렬화된다.
     */
    @Transactional
    public void disconnect(UUID userId) {
        SlackUserLink link = slackUserLinkRepository.findByUserId(userId).orElse(null);
        if (link == null) {
            return;
        }
        UUID installationId = link.getInstallation().getId();
        SlackInstallation installation = slackInstallationRepository.findByIdForUpdate(installationId).orElse(null);
        if (installation == null) {
            return; // 동시 해제가 먼저 설치까지 지웠다
        }
        // 락 앞에서 기다리는 동안 동시 해제가 링크를 지웠을 수 있다 — 락 뒤에 다시 읽는다
        link = slackUserLinkRepository.findByUserId(userId).orElse(null);
        if (link == null || !link.getInstallation().getId().equals(installationId)) {
            return;
        }
        slackUserLinkRepository.delete(link);
        if (slackUserLinkRepository.countByInstallation_Id(installationId) > 0) {
            return; // 같은 워크스페이스를 쓰는 사람이 남았다 — 설치·토큰 유지
        }
        RevokeTarget target = collectForRevoke(installation);
        slackInstallationRepository.delete(installation);
        if (target != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revokeAfterCommit(target);
                }
            });
        }
    }

    /**
     * 락 잡은 엔티티에서 바로 복호화한다 — getValidBotToken은 REQUIRES_NEW라 이 행 락을 기다려 교착한다.
     * 복호화가 실패하면 null(revoke만 건너뛴다 — 해제·탈퇴를 막지 않는다).
     */
    private RevokeTarget collectForRevoke(SlackInstallation installation) {
        try {
            String refresh = installation.getRefreshToken() == null ? null : aesEncryptor.decrypt(installation.getRefreshToken());
            return new RevokeTarget(installation.getTeamId(), aesEncryptor.decrypt(installation.getBotToken()), refresh,
                installation.needsRefresh(LocalDateTime.now()));
        } catch (CustomException e) {
            log.warn("[SlackDisconnectService] 토큰 복호화 실패로 revoke 건너뜀 — team: {}, code: {}",
                installation.getTeamId(), e.getErrorCode());
            return null;
        }
    }

    /** 만료 임박이면 refresh 교환으로 얻은 새 access를, 아니면 저장된 봇 토큰을 폐기한다. 어떤 실패도 던지지 않는다(이미 커밋됨) */
    private void revokeAfterCommit(RevokeTarget target) {
        try {
            String token = target.botToken();
            if (target.expiring()) {
                if (target.refreshToken() == null) {
                    log.warn("[SlackDisconnectService] 만료 임박인데 refresh 없음 — revoke 건너뜀, team: {}", target.teamId());
                    return;
                }
                token = slackTokenService.exchangeRefreshToken(target.refreshToken()).accessToken();
            }
            slackApiClient.revokeQuietly(token);
        } catch (RuntimeException e) {
            String code = e instanceof CustomException ce ? ce.getErrorCode().name() : e.getClass().getSimpleName();
            log.warn("[SlackDisconnectService] revoke 건너뜀 — team: {}, cause: {}", target.teamId(), code);
        }
    }
}
