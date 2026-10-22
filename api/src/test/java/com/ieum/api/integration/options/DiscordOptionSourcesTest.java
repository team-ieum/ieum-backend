package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.service.DiscordApiClient;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

/**
 * Discord 드롭다운 공급원(spec §10) — 호출 모양·표시 이름 규칙·오류 매핑. 서버 목록은 병렬 호출이라 기대 순서를 보지 않는다.
 * 요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다 — 요청이 나가면 테스트가 깨진다.
 */
class DiscordOptionSourcesTest {

    private static final String API = "https://discord.com/api/v10";
    private static final String G1 = "111111111111111111";
    private static final String G2 = "222222222222222222";
    private static final String G3 = "333333333333333333";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    private final DiscordGuildLinkRepository linkRepository = mock(DiscordGuildLinkRepository.class);
    private final DiscordApiClient client = new DiscordApiClient(restTemplate, "cid", "secret", "bot-token");
    private final DiscordGuildOptionSource guilds = new DiscordGuildOptionSource(linkRepository, client);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private void linked(DiscordGuildLink... links) {
        given(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).willReturn(List.of(links));
    }

    private DiscordGuildLink link(String guildId, String storedName) {
        return new DiscordGuildLink(userId, guildId, storedName, "U1");
    }

    private static ResponseCreator json(HttpStatus status, String body) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private void expectGuild(String guildId, ResponseCreator response) {
        server.expect(once(), requestTo(API + "/guilds/" + guildId))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bot bot-token"))
            .andRespond(response);
    }

    static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("키는 discord.guilds")
    void guildsKey() {
        assertThat(guilds.key()).isEqualTo("discord.guilds");
    }

    @Test
    @DisplayName("링크마다 GET /guilds/{id} — 실시간 이름, 연결 순서, nextCursor null. DB 이름은 갱신하지 않는다")
    void liveNamesInLinkOrder() {
        linked(link(G1, "옛 이름1"), link(G2, "옛 이름2"));
        expectGuild(G1, withSuccess("{\"id\":\"" + G1 + "\",\"name\":\"이음\"}", MediaType.APPLICATION_JSON));
        expectGuild(G2, withSuccess("{\"id\":\"" + G2 + "\",\"name\":\"스터디\"}", MediaType.APPLICATION_JSON));

        OptionPage page = guilds.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(new OptionItem(G1, "이음"), new OptionItem(G2, "스터디"));
        assertThat(page.nextCursor()).isNull();
        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("봇 없음(404·10004, 403·50001)은 빼지 않고 '(봇 없음 · 다시 연결)'을 붙인다")
    void botMissingIsMarked() {
        linked(link(G1, "이음"), link(G2, "스터디"));
        expectGuild(G1, json(HttpStatus.NOT_FOUND, "{\"message\":\"Unknown Guild\",\"code\":10004}"));
        expectGuild(G2, json(HttpStatus.FORBIDDEN, "{\"message\":\"Missing Access\",\"code\":50001}"));

        assertThat(guilds.fetch(userId, Map.of(), null).items()).containsExactly(
            new OptionItem(G1, "이음 (봇 없음 · 다시 연결)"),
            new OptionItem(G2, "스터디 (봇 없음 · 다시 연결)"));
    }

    @Test
    @DisplayName("일시 오류(5xx·429 retry_after>5초·네트워크)·봇 토큰 거절(401, 재시도 없음)은 DB 이름으로 대체")
    void transientErrorsFallBackToStoredName() {
        DiscordGuildLink noName = link(G3, null);
        linked(link(G1, "이음"), link(G2, "스터디"), noName);
        expectGuild(G1, withServerError());
        expectGuild(G2, json(HttpStatus.TOO_MANY_REQUESTS, "{\"retry_after\":10.0,\"global\":false}"));
        expectGuild(G3, withException(new SocketTimeoutException()));

        assertThat(guilds.fetch(userId, Map.of(), null).items()).containsExactly(
            new OptionItem(G1, "이음"), new OptionItem(G2, "스터디"), new OptionItem(G3, G3));
    }

    @Test
    @DisplayName("401(봇 토큰 무효)은 한 번만 부르고 DB 이름으로 대체")
    void unauthorizedFallsBackWithoutRetry() {
        linked(link(G1, "이음"));
        expectGuild(G1, json(HttpStatus.UNAUTHORIZED, "{\"message\":\"401: Unauthorized\",\"code\":0}"));

        assertThat(guilds.fetch(userId, Map.of(), null).items()).containsExactly(new OptionItem(G1, "이음"));
    }

    @Test
    @DisplayName("링크가 없으면 DISCORD_NOT_CONNECTED — Discord 호출 없음")
    void noLinksThrowsNotConnected() {
        linked();

        assertErrorCode(() -> guilds.fetch(userId, Map.of(), null), ErrorCode.DISCORD_NOT_CONNECTED);
    }
}
