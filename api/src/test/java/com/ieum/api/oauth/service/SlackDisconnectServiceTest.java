package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.common.util.AesEncryptor;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * 해제의 트랜잭션 경계(spec §6) — 실제 트랜잭션·H2·AES. Slack 호출(refresh 교환·revoke)이 "커밋 뒤"인지는 호출자 롤백
 * 테스트로 본다(커밋 전에 나갔다면 롤백돼도 요청이 이미 나가 서버 검증이 깨진다).
 */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class SlackDisconnectServiceTest {

    private static final String REVOKE_URL = "https://slack.com/api/auth.revoke";
    private static final String REFRESH_URL = "https://slack.com/api/oauth.v2.access";

    @Autowired private SlackDisconnectService slackDisconnectService;
    @Autowired private SlackInstallationRepository installationRepository;
    @Autowired private SlackUserLinkRepository linkRepository;
    @Autowired private AesEncryptor aes;
    @Autowired private RestTemplate restTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void bindServer() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @AfterEach
    void verifyAndClean() {
        // 기대를 등록한 호출이 다 나갔는지만 본다(기대가 없으면 그냥 통과). 'Slack 호출 0건'은 이게 아니라, 기대 없는 요청이
        // 그 시점에 AssertionError(No further requests expected)를 던져 테스트를 깨는 것이 보장한다 — Error라 어느 catch도 삼키지 않는다
        server.verify();
        linkRepository.deleteAll();
        installationRepository.deleteAll();
    }

    /** botToken·refreshToken은 저장될 값 그대로(암호문 또는 손상값) */
    private SlackInstallation installation(String teamId, String botToken, String refreshToken, LocalDateTime expiresAt,
                                           UUID... users) {
        SlackInstallation installation = new SlackInstallation(teamId);
        installation.applyInstall(botToken, refreshToken, expiresAt, "chat:write", "팀", "UBOT", "A1");
        installationRepository.save(installation);
        for (UUID user : users) {
            linkRepository.save(new SlackUserLink(user, installation, "U" + user.toString().substring(0, 8)));
        }
        return installation;
    }

    private void expectRevoke(String token) {
        server.expect(requestTo(REVOKE_URL))
            .andExpect(header("Authorization", "Bearer " + token))
            .andRespond(withSuccess("{\"ok\":true,\"revoked\":true}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("내 링크가 없으면 아무것도 안 한다(멱등) — 다른 사용자 링크·설치는 그대로, Slack 호출도 없다")
    void noLinkIsNoop() {
        UUID other = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxb-bot"), null, null, other);

        slackDisconnectService.disconnect(UUID.randomUUID());

        assertThat(linkRepository.findByUserId(other)).isPresent();
        assertThat(installationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자 링크가 남으면 내 링크만 지우고 설치·토큰은 유지, revoke 없음")
    void othersRemainKeepsInstallation() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxb-bot"), null, null, me, other);

        slackDisconnectService.disconnect(me);

        assertThat(linkRepository.findByUserId(me)).isEmpty();
        assertThat(linkRepository.findByUserId(other)).isPresent();
        assertThat(installationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 사용자 → 링크·설치 삭제, 커밋 뒤 저장 토큰으로 revoke")
    void lastUserDeletesInstallationAndRevokes() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxb-bot"), null, null, me);
        expectRevoke("xoxb-bot");

        slackDisconnectService.disconnect(me);

        assertThat(linkRepository.count()).isZero();
        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("[Review Focus 5] 호출자 트랜잭션(회원 탈퇴)이 롤백되면 revoke하지 않고 링크·설치도 남는다 — revoke는 커밋 뒤에만")
    void callerRollbackSkipsRevoke() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxb-bot"), null, null, me);

        transactionTemplate.executeWithoutResult(status -> {
            slackDisconnectService.disconnect(me);
            status.setRollbackOnly(); // 사용자 삭제가 실패한 경우
        });

        assertThat(linkRepository.findByUserId(me)).isPresent();
        assertThat(installationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("호출자 롤백 + 만료 임박(rotation 켜짐) — refresh 교환도 revoke도 나가지 않는다(1회용 refresh를 소비하지 않음)")
    void callerRollbackSkipsRefreshWhenExpiring() {
        UUID me = UUID.randomUUID();
        String storedRefresh = aes.encrypt("xoxe-1-old");
        installation("T1", aes.encrypt("xoxe.xoxb-old"), storedRefresh, LocalDateTime.now().plusMinutes(1), me);

        transactionTemplate.executeWithoutResult(status -> {
            slackDisconnectService.disconnect(me);
            status.setRollbackOnly();
        });

        assertThat(linkRepository.findByUserId(me)).isPresent();
        assertThat(installationRepository.findAll()).singleElement()
            .extracting(SlackInstallation::getRefreshToken).isEqualTo(storedRefresh);
    }

    @Test
    @DisplayName("커밋 뒤 refresh 교환 뒤 새 토큰으로 revoke")
    void expiringTokenIsRefreshedBeforeRevoke() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxe.xoxb-old"), aes.encrypt("xoxe-1-old"), LocalDateTime.now().plusMinutes(1), me);
        server.expect(requestTo(REFRESH_URL))
            .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "refresh_token", "xoxe-1-old")))
            .andRespond(withSuccess("""
                {"ok":true,"access_token":"xoxe.xoxb-new","refresh_token":"xoxe-1-new","expires_in":43200}""",
                MediaType.APPLICATION_JSON));
        expectRevoke("xoxe.xoxb-new");

        slackDisconnectService.disconnect(me);

        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("refresh가 거절되면 revoke만 건너뛰고 삭제는 진행")
    void refreshRejectedSkipsRevoke() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxe.xoxb-old"), aes.encrypt("xoxe-1-old"), LocalDateTime.now().minusHours(1), me);
        server.expect(requestTo(REFRESH_URL))
            .andRespond(withSuccess("{\"ok\":false,\"error\":\"invalid_refresh_token\"}", MediaType.APPLICATION_JSON));

        slackDisconnectService.disconnect(me);

        assertThat(linkRepository.count()).isZero();
        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("커밋 뒤 refresh 교환이 HTTP 5xx여도 예외 없이 끝나고 revoke는 없다 — 삭제는 유지")
    void refreshServerErrorSkipsRevoke() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxe.xoxb-old"), aes.encrypt("xoxe-1-old"), LocalDateTime.now().plusMinutes(1), me);
        server.expect(requestTo(REFRESH_URL)).andRespond(withServerError());

        slackDisconnectService.disconnect(me);

        assertThat(linkRepository.count()).isZero();
        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("만료 임박인데 저장된 refresh가 없으면 Slack 호출 없이 삭제만")
    void expiringWithoutRefreshSkipsRevoke() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxe.xoxb-old"), null, LocalDateTime.now().minusMinutes(1), me);

        slackDisconnectService.disconnect(me);

        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("revoke가 실패해도(HTTP 5xx) 예외 없이 끝난다 — 삭제는 이미 커밋됐다")
    void revokeFailureIsSwallowed() {
        UUID me = UUID.randomUUID();
        installation("T1", aes.encrypt("xoxb-bot"), null, null, me);
        server.expect(requestTo(REVOKE_URL)).andRespond(withServerError());

        slackDisconnectService.disconnect(me);

        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("[Review Focus 3] 봇 토큰을 복호화할 수 없어도(키 교체·손상) Slack 호출 없이 링크·설치를 지운다")
    void undecryptableTokenStillDisconnects() {
        UUID me = UUID.randomUUID();
        installation("T1", "not-an-aes-ciphertext", null, null, me);

        slackDisconnectService.disconnect(me);

        assertThat(linkRepository.count()).isZero();
        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 사용자 해제가 동시에 두 번 와도(마지막 사용자) 둘 다 예외 없이 끝난다 — 링크·설치 삭제, revoke는 한 번")
    void concurrentDoubleDisconnectLastUser() throws Exception {
        UUID me = UUID.randomUUID();
        SlackInstallation installation = installation("T1", aes.encrypt("xoxb-bot"), null, null, me);
        expectRevoke("xoxb-bot");

        disconnectTwiceWhileLocked(installation.getId(), me);

        assertThat(linkRepository.count()).isZero();
        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("공유 설치에서 같은 사용자 해제가 동시에 두 번 와도 둘 다 예외 없이 끝난다 — 내 링크만 삭제, 설치 유지")
    void concurrentDoubleDisconnectSharedInstallation() throws Exception {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        SlackInstallation installation = installation("T1", aes.encrypt("xoxb-bot"), null, null, me, other);

        disconnectTwiceWhileLocked(installation.getId(), me);

        assertThat(linkRepository.findByUserId(me)).isEmpty();
        assertThat(linkRepository.findByUserId(other)).isPresent();
        assertThat(installationRepository.count()).isEqualTo(1);
    }

    /** 보조 트랜잭션이 설치 행 락을 쥔 동안 두 해제가 링크를 읽고 락 앞에 줄 서게 한 뒤 놓는다. 해제가 던지면 get()이 실패한다(컨트롤러에선 500) */
    private void disconnectTwiceWhileLocked(UUID installationId, UUID userId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                installationRepository.findByIdForUpdate(installationId);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> first = pool.submit(() -> slackDisconnectService.disconnect(userId));
            Future<?> second = pool.submit(() -> slackDisconnectService.disconnect(userId));
            Thread.sleep(300); // 둘 다 링크를 읽고 락 앞에 설 시간 — H2 락 대기 한도(약 1~2초)보다 짧게
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
