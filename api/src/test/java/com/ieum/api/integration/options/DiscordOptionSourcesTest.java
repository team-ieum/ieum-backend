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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
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

    private final DiscordChannelOptionSource channels = new DiscordChannelOptionSource(linkRepository, client);

    private void ownsGuild(String guildId) {
        given(linkRepository.findByUserIdAndGuildId(userId, guildId)).willReturn(Optional.of(link(guildId, "이음")));
    }

    private static String channel(String id, String name, int type, int position) {
        return "{\"id\":\"%s\",\"name\":\"%s\",\"type\":%d,\"position\":%d,\"guild_id\":\"%s\"}"
            .formatted(id, name, type, position, G1);
    }

    private static String channelList(String... channels) {
        return Stream.of(channels).collect(Collectors.joining(",", "[", "]"));
    }

    private void expectChannels(ResponseCreator response) {
        server.expect(once(), requestTo(API + "/guilds/" + G1 + "/channels"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bot bot-token"))
            .andRespond(response);
    }

    private static final String MIXED = channelList(
        channel("9001", "random", 0, 2),
        channel("9002", "voice", 2, 0),          // 음성 — 제외
        channel("9003", "공지", 5, 1),            // 공지 — 포함
        channel("9004", "General", 0, 0),
        channel("9005", "카테고리", 4, 0),         // 카테고리 — 제외
        channel("9006", "forum", 15, 3));        // 포럼 — 제외

    @Test
    @DisplayName("키는 discord.channels")
    void channelsKey() {
        assertThat(channels.key()).isEqualTo("discord.channels");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{{nodes.a.output.guild_id}}"})
    @DisplayName("guild_id가 비었거나 참조식이면 INVALID_INPUT — Discord 호출 없음")
    void guildIdRequired(String guildId) {
        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", guildId), null), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("guild_id 입력 자체가 없어도 INVALID_INPUT")
    void guildIdMissing() {
        assertErrorCode(() -> channels.fetch(userId, Map.of(), null), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("내가 연결하지 않은 서버면 DISCORD_GUILD_NOT_CONNECTED(403) — 봇이 들어가 있어도 남의 서버 채널은 보여 주지 않는다")
    void othersGuildRejected() {
        given(linkRepository.findByUserIdAndGuildId(userId, G1)).willReturn(Optional.empty());

        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", G1), null), ErrorCode.DISCORD_GUILD_NOT_CONNECTED);
    }

    @Test
    @DisplayName("텍스트(0)·공지(5)만, 카테고리·위치 순, (채널 ID, #이름), nextCursor null. guild_id 앞뒤 공백은 뗀다")
    void filtersSendableAndSortsByPosition() {
        ownsGuild(G1);
        expectChannels(withSuccess(MIXED, MediaType.APPLICATION_JSON));

        OptionPage page = channels.fetch(userId, Map.of("guild_id", " " + G1 + " "), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("9004", "#General"), new OptionItem("9003", "#공지"), new OptionItem("9001", "#random"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("Discord 화면 순서 — 카테고리 없는 채널 먼저, 그다음 카테고리 position·id 순으로 묶고, 안에서 position·id 숫자 순")
    void sortsByCategoryThenPosition() {
        ownsGuild(G1);
        String json = """
            [
              {"id":"100","name":"뒤-카테고리","type":4,"position":1},
              {"id":"200","name":"앞-카테고리","type":4,"position":0},
              {"id":"31","name":"a1","type":0,"position":0,"parent_id":"100"},
              {"id":"100000","name":"b-tie-big","type":0,"position":0,"parent_id":"200"},
              {"id":"99999","name":"b-tie-small","type":5,"position":0,"parent_id":"200"},
              {"id":"33","name":"b2","type":0,"position":1,"parent_id":"200"},
              {"id":"34","name":"a2","type":0,"position":1,"parent_id":"100"},
              {"id":"35","name":"nocat","type":0,"position":5,"parent_id":null},
              {"id":"400","name":"같은위치-큰id","type":4,"position":2},
              {"id":"300","name":"같은위치-작은id","type":4,"position":2},
              {"id":"41","name":"d1","type":0,"position":0,"parent_id":"400"},
              {"id":"42","name":"c2","type":0,"position":1,"parent_id":"300"},
              {"id":"43","name":"d2","type":0,"position":1,"parent_id":"400"},
              {"id":"44","name":"c1","type":0,"position":0,"parent_id":"300"}
            ]""";
        expectChannels(withSuccess(json, MediaType.APPLICATION_JSON));

        // position이 같은 카테고리(300·400)는 카테고리 id 순으로 묶여 채널이 섞이지 않는다
        assertThat(channels.fetch(userId, Map.of("guild_id", G1), null).items()).extracting(OptionItem::name)
            .containsExactly("#nocat", "#b-tie-small", "#b-tie-big", "#b2", "#a1", "#a2", "#c1", "#c2", "#d1", "#d2");
    }

    @Test
    @DisplayName("[Review Focus 4] 검색어의 앞 #·대소문자·앞뒤 공백을 무시한다 — 라벨이 #이름이라 사용자는 '#Gen '을 친다")
    void searchIgnoresHashCaseAndSpaces() {
        ownsGuild(G1);
        expectChannels(withSuccess(MIXED, MediaType.APPLICATION_JSON));

        assertThat(channels.fetch(userId, Map.of("guild_id", G1, "q", " #Gen "), null).items())
            .containsExactly(new OptionItem("9004", "#General"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "#", " # "})
    @DisplayName("공백뿐인 q·#만 있는 q는 없는 것으로 본다")
    void blankQueryIsNoQuery(String q) {
        ownsGuild(G1);
        expectChannels(withSuccess(MIXED, MediaType.APPLICATION_JSON));

        assertThat(channels.fetch(userId, Map.of("guild_id", G1, "q", q), null).items()).hasSize(3);
    }

    @Test
    @DisplayName("봇 없음(404·10004)·(403·50001) → AUTHENTICATION_REQUIRED + 다시 연결 문구")
    void botMissingAsksReconnect() {
        ownsGuild(G1);
        expectChannels(json(HttpStatus.FORBIDDEN, "{\"message\":\"Missing Access\",\"code\":50001}"));

        assertThatThrownBy(() -> channels.fetch(userId, Map.of("guild_id", G1), null))
            .isInstanceOf(CustomException.class)
            .hasMessage(DiscordChannelOptionSource.BOT_MISSING_MESSAGE)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("404(Unknown Guild)도 봇 없음")
    void unknownGuildAsksReconnect() {
        ownsGuild(G1);
        expectChannels(json(HttpStatus.NOT_FOUND, "{\"message\":\"Unknown Guild\",\"code\":10004}"));

        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", G1), null), ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("401(봇 토큰 무효) → DISCORD_API_UNAVAILABLE, 재시도 없음")
    void unauthorizedIsUnavailable() {
        ownsGuild(G1);
        expectChannels(json(HttpStatus.UNAUTHORIZED, "{\"message\":\"401: Unauthorized\",\"code\":0}"));

        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", G1), null), ErrorCode.DISCORD_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("5xx·네트워크·429(retry_after>5초) → DISCORD_API_UNAVAILABLE")
    void transientIsUnavailable() {
        ownsGuild(G1);
        expectChannels(withServerError());
        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", G1), null), ErrorCode.DISCORD_API_UNAVAILABLE);

        server.reset();
        expectChannels(withException(new SocketTimeoutException()));
        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", G1), null), ErrorCode.DISCORD_API_UNAVAILABLE);

        server.reset();
        expectChannels(json(HttpStatus.TOO_MANY_REQUESTS, "{\"retry_after\":30.0}"));
        assertErrorCode(() -> channels.fetch(userId, Map.of("guild_id", G1), null), ErrorCode.DISCORD_API_UNAVAILABLE);
    }
}
