package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.between;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * 해제의 트랜잭션 경계(spec §6) — 실제 트랜잭션·H2. 퇴장(DELETE /users/@me/guilds/{id})이 "커밋 뒤 재카운트"인지는 호출자 롤백·
 * 재카운트 전 링크 생김·동시 해제로 본다. 기대를 등록하지 않은 Discord 호출은 그 시점에 AssertionError로 테스트를 깬다.
 */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class DiscordDisconnectServiceTest {

    private static final String G1 = "111111111111111111";
    private static final String G2 = "222222222222222222";

    @Autowired private DiscordDisconnectService discordDisconnectService;
    @Autowired private DiscordGuildLinkRepository linkRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RestTemplate restTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    private MockRestServiceServer server;
    private final List<UUID> users = new ArrayList<>();

    @BeforeEach
    void bindServer() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @AfterEach
    void verifyAndClean() {
        try {
            server.verify();
        } finally {
            linkRepository.deleteAll();
            userRepository.deleteAllById(users);
        }
    }

    /** 해제는 users 행을 먼저 잠근다 — 행이 있어야 링크를 지운다 */
    private UUID user() {
        UUID id = userRepository.save(User.builder().email(UUID.randomUUID() + "@ieum.test").name("u")
            .provider(AuthProvider.LOCAL).role(UserRole.ROLE_USER).build()).getId();
        users.add(id);
        return id;
    }

    private void link(UUID userId, String guildId) {
        linkRepository.save(new DiscordGuildLink(userId, guildId, "서버", "U" + userId.toString().substring(0, 8)));
    }

    private static String leaveUrl(String guildId) {
        return "https://discord.com/api/v10/users/@me/guilds/" + guildId;
    }

    private void expectLeave(String guildId) {
        server.expect(requestTo(leaveUrl(guildId)))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header("Authorization", "Bot test-discord-bot-token"))
            .andExpect(header("User-Agent", "DiscordBot (https://green.kiacms.co.kr, 1.0)"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Test
    @DisplayName("[Review Focus 1] 내 링크가 없으면 퇴장 판정도 하지 않는다(멱등) — 남은 링크 0인 서버여도 Discord 호출 없음")
    void noLinkIsNoop() {
        discordDisconnectService.disconnect(user(), G1);

        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("[Review Focus 1] 남의 서버 ID로 해제 — 남의 링크는 그대로, 퇴장 판정·Discord 호출 없음")
    void othersGuildIdIsNoop() {
        UUID other = UUID.randomUUID();
        link(other, G1);

        discordDisconnectService.disconnect(user(), G1);

        assertThat(linkRepository.findByUserIdAndGuildId(other, G1)).isPresent();
    }

    @Test
    @DisplayName("같은 서버에 다른 사용자 링크가 남으면 내 링크만 지우고 봇은 남는다")
    void othersRemainKeepsBot() {
        UUID me = user();
        UUID other = UUID.randomUUID();
        link(me, G1);
        link(other, G1);

        discordDisconnectService.disconnect(me, G1);

        assertThat(linkRepository.findByUserIdAndGuildId(me, G1)).isEmpty();
        assertThat(linkRepository.findByUserIdAndGuildId(other, G1)).isPresent();
    }

    @Test
    @DisplayName("마지막 링크 → 커밋 뒤 재카운트 0 → 봇 퇴장. 내 다른 서버 링크는 그대로")
    void lastLinkLeavesAfterCommit() {
        UUID me = user();
        link(me, G1);
        link(me, G2);
        expectLeave(G1);

        discordDisconnectService.disconnect(me, G1);

        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(me))
            .extracting(DiscordGuildLink::getGuildId).containsExactly(G2);
    }

    @Test
    @DisplayName("[Review Focus 3] 호출자 트랜잭션(회원 탈퇴)이 롤백되면 퇴장하지 않고 링크도 남는다 — 퇴장은 커밋 뒤에만")
    void callerRollbackSkipsLeave() {
        UUID me = user();
        link(me, G1);

        transactionTemplate.executeWithoutResult(status -> {
            discordDisconnectService.disconnectAll(me);
            status.setRollbackOnly(); // 사용자 삭제가 실패한 경우
        });

        assertThat(linkRepository.findByUserIdAndGuildId(me, G1)).isPresent();
    }

    @Test
    @DisplayName("커밋과 재카운트 사이에 다른 사용자가 같은 서버를 연결했으면 퇴장하지 않는다")
    void linkCreatedBeforeRecountKeepsBot() {
        UUID me = user();
        UUID newcomer = UUID.randomUUID();
        link(me, G1);

        transactionTemplate.executeWithoutResult(status -> {
            discordDisconnectService.disconnect(me, G1);
            // 바깥 트랜잭션이 커밋되기 전에 다른 트랜잭션이 같은 서버 링크를 커밋한다(동시 연결 콜백)
            requiresNew().executeWithoutResult(inner -> link(newcomer, G1));
        });

        assertThat(linkRepository.countByGuildId(G1)).isEqualTo(1);
    }

    @Test
    @DisplayName("두 링크 동시 해제 — 둘 다 트랜잭션 안에선 상대 링크를 보지만(1·1) 커밋 뒤 재카운트라 봇은 최소 한 번 나간다")
    void concurrentLastTwoDisconnectsLeaveAtLeastOnce() throws Exception {
        UUID a = user();
        UUID b = user();
        link(a, G1);
        link(b, G1);
        // 나중에 커밋한 쪽은 반드시 0을 본다. 둘 다 0을 보면 두 번째 퇴장은 404(이미 없음)가 되지만 여기선 둘 다 204로 받는다
        server.expect(between(1, 2), requestTo(leaveUrl(G1)))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
        CyclicBarrier bothDeleted = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = pool.submit(disconnectThenWait(a, bothDeleted));
            Future<Long> second = pool.submit(disconnectThenWait(b, bothDeleted));

            // 트랜잭션 안에서 셌다면 둘 다 1 → 아무도 퇴장하지 않았을 것이다(spec §6이 막는 버그)
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(linkRepository.countByGuildId(G1)).isZero();
    }

    /** 해제 → 트랜잭션 안 카운트 → 두 스레드가 모두 지울 때까지 기다린 뒤 커밋. 반환값은 트랜잭션 안에서 본 링크 수 */
    private Callable<Long> disconnectThenWait(UUID userId, CyclicBarrier bothDeleted) {
        return () -> transactionTemplate.execute(status -> {
            discordDisconnectService.disconnect(userId, G1);
            long seenInside = linkRepository.countByGuildId(G1);
            try {
                bothDeleted.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return seenInside;
        });
    }

    @Test
    @DisplayName("같은 링크 동시 해제(더블클릭·해제×탈퇴) — 둘 다 예외 없이 끝나고 퇴장은 한 번만")
    void concurrentSameLinkDisconnectIsSafe() throws Exception {
        UUID me = user();
        link(me, G1);
        expectLeave(G1); // 실제로 지운 쪽만 퇴장 판정 — 0행 삭제 쪽은 호출하지 않는다
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        Callable<Void> disconnect = () -> transactionTemplate.execute(status -> {
            // 둘 다 링크를 읽은 뒤에 해제를 시작한다 — 엔티티 delete면 나중 커밋이 0행 삭제로 StaleObjectStateException
            assertThat(linkRepository.findByUserIdAndGuildId(me, G1)).isPresent();
            try {
                bothLoaded.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            discordDisconnectService.disconnect(me, G1);
            return null;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = pool.submit(disconnect);
            Future<Void> second = pool.submit(disconnect);

            assertThatCode(() -> first.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThatCode(() -> second.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }
        assertThat(linkRepository.countByGuildId(G1)).isZero();
    }

    @Test
    @DisplayName("users 행이 없으면(탈퇴) 링크가 남아 있어도 아무것도 안 한다 — 탈퇴 정리는 disconnectAll 몫")
    void missingUserIsNoop() {
        UUID gone = UUID.randomUUID();
        link(gone, G1);

        discordDisconnectService.disconnect(gone, G1);

        assertThat(linkRepository.findByUserIdAndGuildId(gone, G1)).isPresent();
    }

    @Test
    @DisplayName("해제는 users 행 락을 먼저 잡는다 — 재연결 콜백(save)이 그 행을 잡고 있으면 커밋까지 기다렸다가 지운다")
    void disconnectWaitsForUserRowLock() throws Exception {
        UUID me = user();
        link(me, G1);
        expectLeave(G1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> disconnect = transactionTemplate.execute(status -> {
                userRepository.findByIdForUpdate(me); // 콜백 save가 잡는 락
                Future<?> f = pool.submit(() -> discordDisconnectService.disconnect(me, G1));
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                assertThat(f).isNotDone();
                return f;
            });
            disconnect.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(linkRepository.findByUserIdAndGuildId(me, G1)).isEmpty();
    }

    @Test
    @DisplayName("퇴장이 실패해도(HTTP 5xx) 예외 없이 끝난다 — 링크 삭제는 이미 커밋됐다")
    void leaveFailureIsSwallowed() {
        UUID me = user();
        link(me, G1);
        server.expect(requestTo(leaveUrl(G1))).andRespond(withServerError());

        assertThatCode(() -> discordDisconnectService.disconnect(me, G1)).doesNotThrowAnyException();
        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("이미 쫓겨난 서버(404·10004)도 예외 없이 끝난다")
    void alreadyGoneIsSwallowed() {
        UUID me = user();
        link(me, G1);
        server.expect(requestTo(leaveUrl(G1)))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Unknown Guild\",\"code\":10004}"));

        assertThatCode(() -> discordDisconnectService.disconnect(me, G1)).doesNotThrowAnyException();
        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("disconnectAll — 내 링크 전부 삭제, 남은 링크가 0이 된 서버만 퇴장")
    void disconnectAllLeavesOnlyOrphans() {
        UUID me = user();
        UUID other = UUID.randomUUID();
        link(me, G1);
        link(me, G2);
        link(other, G2);
        expectLeave(G1);

        discordDisconnectService.disconnectAll(me);

        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(me)).isEmpty();
        assertThat(linkRepository.findByUserIdAndGuildId(other, G2)).isPresent();
    }

    @Test
    @DisplayName("leaveIfOrphan을 트랜잭션 밖(설치 콜백)에서 부르면 바로 재카운트 → 0이면 퇴장")
    void leaveIfOrphanOutsideTransactionRunsNow() {
        expectLeave(G1);

        discordDisconnectService.leaveIfOrphan(G1);
    }

    @Test
    @DisplayName("leaveIfOrphan — 링크가 남아 있으면 Discord 호출 없음")
    void leaveIfOrphanKeepsBotWhenLinked() {
        link(UUID.randomUUID(), G1);

        discordDisconnectService.leaveIfOrphan(G1);
    }
}
