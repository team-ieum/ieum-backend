package com.ieum.api.trigger.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.GoogleApiReader;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.service.GoogleTokenService;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.PollStart;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class GmailTriggerClientTest {

    private static final String LIST = "https://gmail.googleapis.com/gmail/v1/users/me/messages";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GoogleTokenService tokenService = mock(GoogleTokenService.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final GmailTriggerClient client =
        new GmailTriggerClient(new GoogleApiReader(tokenService, accounts, restTemplate));
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verify() {
        server.verify();
    }

    private void connected() {
        given(accounts.findByUserIdAndProvider(userId, AuthProvider.GOOGLE)).willReturn(Optional.of(
            ConnectedAccount.builder().userId(userId).provider(AuthProvider.GOOGLE).accessToken("enc")
                .scopes("openid https://www.googleapis.com/auth/gmail.modify").build()));
        given(tokenService.getValidAccessToken(userId)).willReturn("g-token");
    }

    @Test
    @DisplayName("baseQuery — 빈 query는 받은편지함에서 보낸 메일 제외, 값이 있으면 trim 후 보낸 메일 제외(자기 발화 루프 방지)")
    void baseQuery() {
        assertThat(GmailTriggerClient.baseQuery(Map.of())).isEqualTo("in:inbox -in:sent");
        assertThat(GmailTriggerClient.baseQuery(Map.of("query", "  "))).isEqualTo("in:inbox -in:sent");
        assertThat(GmailTriggerClient.baseQuery(Map.of("query", " from:a "))).isEqualTo("(from:a) -in:sent");
        assertThat(GmailTriggerClient.baseQuery(null)).isEqualTo("in:inbox -in:sent");
    }

    @Test
    @DisplayName("listMessageIds — q는 엄격 인코딩, 결과 없으면 빈 목록")
    void listEncodesQuery() {
        connected();
        server.expect(requestTo(LIST + "?q=%28from%3Aa%40b.c%29%20after%3A99&maxResults=500"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer g-token"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m2\"},{\"id\":\"m1\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST + "?q=in%3Ainbox&maxResults=1"))
            .andRespond(withSuccess("{\"resultSizeEstimate\":0}", MediaType.APPLICATION_JSON));

        assertThat(client.listMessageIds(userId, "(from:a@b.c) after:99", 500)).containsExactly("m2", "m1");
        assertThat(client.listMessageIds(userId, "in:inbox", 1)).isEmpty();
    }

    @Test
    @DisplayName("listAllMessageIds — nextPageToken을 끝까지 따라간다")
    void listAllFollowsPages() {
        connected();
        server.expect(requestTo(LIST + "?q=in%3Ainbox&maxResults=500"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m3\"}],\"nextPageToken\":\"p+2\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST + "?q=in%3Ainbox&maxResults=500&pageToken=p%2B2"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m2\"},{\"id\":\"m1\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.listAllMessageIds(userId, "in:inbox")).containsExactly("m3", "m2", "m1");
    }

    @Test
    @DisplayName("listAllMessageIds — 10페이지를 넘으면 INVALID_INPUT으로 끊는다(메일함 전체를 받지 않게)")
    void listAllStopsAtPageCap() {
        connected();
        server.expect(requestTo(LIST + "?q=in%3Ainbox&maxResults=500"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m\"}],\"nextPageToken\":\"p\"}", MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.times(9),
                requestTo(LIST + "?q=in%3Ainbox&maxResults=500&pageToken=p"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m\"}],\"nextPageToken\":\"p\"}", MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.listAllMessageIds(userId, "in:inbox"))
            .isInstanceOf(com.ieum.common.exception.CustomException.class)
            .hasMessageContaining("5000건");
    }

    @Test
    @DisplayName("노드 테스트 — FETCH 방식, fetchSample은 최신 1건을 정규화하고 0건이면 empty")
    void fetchSample() {
        connected();
        server.expect(requestTo(LIST + "?q=%28label%3Awork%29%20-in%3Asent&maxResults=1"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m9\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST + "/m9?format=full"))
            .andRespond(withSuccess("{\"id\":\"m9\",\"threadId\":\"t\",\"internalDate\":\"1000\",\"payload\":{}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST + "?q=in%3Ainbox%20-in%3Asent&maxResults=1"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.type()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(client.nodeTestMode()).isEqualTo(NodeTestMode.FETCH);
        assertThat(client.fetchSample(userId, Map.of("query", "label:work")))
            .hasValueSatisfying(out -> assertThat(out).containsEntry("messageId", "m9"));
        assertThat(client.fetchSample(userId, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("TriggerHandler — type은 GMAIL_NEW_EMAIL, 실행 출력은 심어 둔 정규화 페이로드 그대로(config 미혼입)")
    void triggerHandlerTypeAndOutput() {
        Map<String, Object> planted = Map.of("messageId", "m1", "subject", "s");
        Node node = new Node("t1", NodeType.TRIGGER, "Gmail", Map.of("query", "from:x"), "gmail.new_email");

        assertThat(client.type()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(client.output(node, Map.of("query", "from:x"), planted)).isEqualTo(planted);
    }

    @Test
    @DisplayName("구독 스냅샷 — query는 trim, blank·생략은 같은 빈 스냅샷(다르면 저장마다 커서가 리셋돼 메일 유실), resource_key 없음")
    void snapshotDropsBlankQuery() {
        assertThat(client.subscribes()).isTrue();
        assertThat(client.snapshot(Map.of("query", "  from:boss@x.com "))).isEqualTo(Map.of("query", "from:boss@x.com"));
        assertThat(client.snapshot(Map.of("query", "   "))).isEmpty();
        assertThat(client.snapshot(Map.of("query", "   "))).isEqualTo(client.snapshot(Map.of()));
        assertThat(client.resourceKey(Map.of("query", "from:x"))).isNull();
        assertThat(GmailTriggerClient.query(null)).isNull();
        assertThat(GmailTriggerClient.query(Map.of("query", 3))).isNull();
    }

    @Test
    @DisplayName("폴링 시작 — 커서 {afterSec: 시작 시각(초)}, 즉시 폴링(나머지 커서 키는 GmailCursor가 채운다)")
    void pollStartsAtNow() {
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 11, 9, 0, 0);

        assertThat(client.pollStart(t0)).isEqualTo(
            new PollStart(Map.of("afterSec", t0.atZone(ZoneId.systemDefault()).toEpochSecond()), t0));
    }
}
