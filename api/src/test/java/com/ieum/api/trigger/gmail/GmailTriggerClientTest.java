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
import com.ieum.workflowcore.domain.enums.TriggerType;
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
    @DisplayName("baseQuery — 빈 query는 받은편지함에서 보낸 메일 제외(자기 발화 루프 방지), 값은 trim")
    void baseQuery() {
        assertThat(GmailTriggerClient.baseQuery(Map.of())).isEqualTo("in:inbox -in:sent");
        assertThat(GmailTriggerClient.baseQuery(Map.of("query", "  "))).isEqualTo("in:inbox -in:sent");
        assertThat(GmailTriggerClient.baseQuery(Map.of("query", " from:a "))).isEqualTo("from:a");
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
    @DisplayName("fetchLatest — 최신 1건을 정규화, 0건이면 empty")
    void fetchLatest() {
        connected();
        server.expect(requestTo(LIST + "?q=label%3Awork&maxResults=1"))
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"m9\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST + "/m9?format=full"))
            .andRespond(withSuccess("{\"id\":\"m9\",\"threadId\":\"t\",\"internalDate\":\"1000\",\"payload\":{}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST + "?q=in%3Ainbox%20-in%3Asent&maxResults=1"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.triggerType()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(client.fetchLatest(userId, Map.of("query", "label:work")))
            .hasValueSatisfying(out -> assertThat(out).containsEntry("messageId", "m9"));
        assertThat(client.fetchLatest(userId, Map.of())).isEmpty();
    }
}
