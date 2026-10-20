package com.ieum.api.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.auth.service.SlackTokenService;
import com.ieum.common.util.AesEncryptor;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/** 실제 트랜잭션 매니저·H2·AES로 갱신의 트랜잭션 경계를 본다. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class SlackTokenServiceIntegrationTest {

    static final String REFRESH_URL = "https://slack.com/api/oauth.v2.access";
    static final String ROTATED = """
        {"ok":true,"access_token":"xoxe.xoxb-new","refresh_token":"xoxe-1-new","expires_in":43200,"token_type":"bot"}""";

    @Autowired private SlackTokenService slackTokenService;
    @Autowired private SlackInstallationRepository installationRepository;
    @Autowired private SlackUserLinkRepository linkRepository;
    @Autowired private AesEncryptor aesEncryptor;
    @Autowired private RestTemplate restTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        linkRepository.deleteAll();
        installationRepository.deleteAll();
    }

    /** 만료 임박(1분 남음) 설치 하나에 users를 모두 링크한다 */
    private SlackInstallation expiringInstallation(UUID... users) {
        SlackInstallation installation = new SlackInstallation("T1");
        installation.applyInstall(aesEncryptor.encrypt("xoxe.xoxb-old"), aesEncryptor.encrypt("xoxe-1-old"),
            LocalDateTime.now().plusMinutes(1), "chat:write", "팀", "UBOT", "A1");
        installationRepository.save(installation);
        for (UUID user : users) {
            linkRepository.save(new SlackUserLink(user, installation, "U" + user.toString().substring(0, 8)));
        }
        return installation;
    }

    @Test
    @DisplayName("호출자 트랜잭션이 롤백돼도 회전된 Slack 토큰은 커밋된다(REQUIRES_NEW — refresh는 1회용)")
    void rotatedTokensSurviveCallerRollback() {
        UUID userId = UUID.randomUUID();
        expiringInstallation(userId);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(REFRESH_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(ROTATED, MediaType.APPLICATION_JSON));

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(slackTokenService.getValidBotToken(userId)).isEqualTo("xoxe.xoxb-new");
            status.setRollbackOnly(); // 노드 실행·드롭다운 쪽 트랜잭션이 다른 이유로 롤백되는 경우
        });

        server.verify();
        SlackInstallation saved = installationRepository.findAll().get(0);
        assertThat(aesEncryptor.decrypt(saved.getRefreshToken())).isEqualTo("xoxe-1-new");
        assertThat(aesEncryptor.decrypt(saved.getBotToken())).isEqualTo("xoxe.xoxb-new");
    }
}
