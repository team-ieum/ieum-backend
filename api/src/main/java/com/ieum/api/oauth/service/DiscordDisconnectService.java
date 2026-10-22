package com.ieum.api.oauth.service;

import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.service.DiscordApiClient;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Discord 연결 해제(spec §6). 내 링크만 지우고, 그 서버의 마지막 IEUM 링크였으면 봇이 서버를 나간다(D5).
 *
 * <p>"마지막인가"는 트랜잭션 안에서 세지 않는다 — 마지막 두 사용자가 동시에 해제하면 READ COMMITTED에서 둘 다 상대 링크를 보고
 * 1을 세어 봇이 영원히 남는다. 공유 행이 없어 락으로 줄 세울 수 없으므로 **커밋 뒤** 새 트랜잭션(REQUIRES_NEW — afterCommit 안의
 * 데이터 접근은 원래 트랜잭션 자원에 묶여 있다)에서 다시 센다. 나중에 커밋한 쪽은 반드시 0을 본다.
 * 재카운트와 퇴장 사이에 누가 같은 서버를 연결하면 봇이 나가 버릴 수 있다 — 드물고 결과가 "다시 연결" 안내라 수용(§13).
 */
@Slf4j
@Service
public class DiscordDisconnectService {

    private final DiscordGuildLinkRepository linkRepository;
    private final DiscordApiClient discordApiClient;
    private final TransactionTemplate requiresNew;

    public DiscordDisconnectService(DiscordGuildLinkRepository linkRepository, DiscordApiClient discordApiClient,
                                    PlatformTransactionManager transactionManager) {
        this.linkRepository = linkRepository;
        this.discordApiClient = discordApiClient;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 내 (userId, guildId) 링크가 없으면 아무것도 안 한다(멱등) — 지운 링크가 없으면 퇴장 판정도 하지 않는다 */
    @Transactional
    public void disconnect(UUID userId, String guildId) {
        if (linkRepository.deleteByUserIdAndGuildId(userId, guildId) > 0) {
            leaveIfOrphan(guildId);
        }
    }

    /** 회원 탈퇴 — 호출자 트랜잭션에 합류한다. 퇴장은 그 트랜잭션이 커밋된 뒤에만 나간다 */
    @Transactional
    public void disconnectAll(UUID userId) {
        List<DiscordGuildLink> links = linkRepository.findAllByUserIdOrderByCreatedAtAsc(userId);
        List<String> guildIds = links.stream().map(DiscordGuildLink::getGuildId).toList();
        linkRepository.deleteAllInBatch(links); // 벌크 — 동시 해제로 이미 지워진 행이 있어도 실패하지 않는다
        // ponytail: 커밋 뒤 서버마다 재카운트·봇 퇴장 HTTP를 요청 스레드에서 차례로 해 탈퇴 응답이 최대 서버 N개×(read timeout 30초+429 대기 5초) 늦어진다. 문제가 되면 비동기 정리 큐로
        guildIds.forEach(this::leaveIfOrphan);
    }

    /**
     * 그 서버에 남은 링크가 0이면 봇이 나간다. 트랜잭션 안에서 부르면 커밋 뒤로 미루고(롤백되면 아무것도 안 한다),
     * 밖(설치 콜백 2-9 — 저장 트랜잭션은 이미 끝났다)에서 부르면 바로 판정한다. 어떤 실패도 던지지 않는다.
     */
    public void leaveIfOrphan(String guildId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    leaveNowIfOrphan(guildId);
                }
            });
            return;
        }
        leaveNowIfOrphan(guildId);
    }

    private void leaveNowIfOrphan(String guildId) {
        Long remaining;
        try {
            remaining = requiresNew.execute(status -> linkRepository.countByGuildId(guildId));
        } catch (RuntimeException e) {
            log.warn("[DiscordDisconnectService] 남은 링크 수 조회 실패로 퇴장 판정 건너뜀 — guild: {}, cause: {}",
                guildId, e.getClass().getSimpleName());
            return;
        }
        if (remaining == null || remaining > 0) {
            return;
        }
        try {
            discordApiClient.leaveGuild(guildId);
            log.info("[DiscordDisconnectService] 마지막 링크 해제 — 봇 퇴장, guild: {}", guildId);
        } catch (RestClientException e) {
            // 동시 해제로 이미 나갔거나(10004·404) Discord 장애 — 해제는 이미 커밋됐다
            log.warn("[DiscordDisconnectService] 봇 퇴장 실패 — guild: {}, {}", guildId, DiscordApiClient.describe(e));
        }
    }
}
