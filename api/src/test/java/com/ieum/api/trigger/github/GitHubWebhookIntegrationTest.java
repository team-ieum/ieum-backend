package com.ieum.api.trigger.github;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.auth.service.GitHubTokenService;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

/**
 * 실제 시큐리티 체인·메시지 컨버터(raw byte[])·H2 구독 행·{@code @Async} 디스패치를 한 번에 태운다(BE-80 이월 조건).
 * Redis 없음은 IeumApplicationTest와 같게 — DefaultIdempotencyStore는 장애 시 진행 허용(true)이라 마킹이 통과한다.
 * 사용자별 GitHub 토큰은 GitHubTokenService 목으로 주고 Authorization 헤더로 구분한다. 실행 생성은 AppTriggerFirer 목으로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class GitHubWebhookIntegrationTest {

    private static final String SECRET = "test-github-webhook-secret";
    private static final String OPENED = """
        {"action": "opened",
         "issue": {"number": 7, "title": "버그", "body": "본문", "html_url": "https://github.com/team/ieum/issues/7",
                   "user": {"login": "octo"}, "labels": [], "created_at": "2026-10-11T01:02:03Z"},
         "repository": {"id": 123, "full_name": "team/ieum"}}""";

    @TestConfiguration
    static class NoRedisConfig {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
            org.mockito.BDDMockito.given(factory.getConnection())
                .willThrow(new RedisConnectionFailureException("테스트 환경엔 Redis가 없다"));
            return factory;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private AppTriggerSubscriptionRepository repository;
    @Autowired private RestTemplate restTemplate;
    @MockitoBean private GitHubTokenService gitHubTokenService;
    @MockitoBean private AppTriggerFirer appTriggerFirer;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    /** 이 토큰의 설치 범위 = 설치 1개(id 1)에 레포 하나. 두 사용자가 같은 URL을 부르므로 Authorization으로 구분한다. */
    private static void expectInstalled(MockRestServiceServer server, String token, long repoId) {
        server.expect(requestTo(startsWith("https://api.github.com/user/installations?")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andRespond(withSuccess("{\"installations\": [{\"id\": 1}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://api.github.com/user/installations/1/repositories?")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andRespond(withSuccess("{\"repositories\": [{\"id\": " + repoId + ", \"full_name\": \"team/r" + repoId + "\"}]}",
                MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("[Review Focus 7] Stub 시기(검증 없이 저장된) 행 — JWT 없는 서명된 webhook 202, 설치 범위 밖 행은 실행 없음·범위 안 행은 실행")
    void stubEraRowOutsideInstallationDoesNotFire() throws Exception {
        UUID outsider = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outWorkflow = UUID.randomUUID();
        UUID inWorkflow = UUID.randomUUID();
        // sync·검증기를 거치지 않는 직접 저장 = BE-80 배포 후 Stub 검증기로 들어온 행
        repository.save(AppTriggerSubscription.create(outWorkflow, "t1", outsider, TriggerType.GITHUB_NEW_ISSUE,
            "123", Map.of("repoId", 123L), LocalDateTime.now()));
        repository.save(AppTriggerSubscription.create(inWorkflow, "t1", member, TriggerType.GITHUB_NEW_ISSUE,
            "123", Map.of("repoId", 123L), LocalDateTime.now()));
        given(gitHubTokenService.getValidAccessToken(outsider)).willReturn("t-out");
        given(gitHubTokenService.getValidAccessToken(member)).willReturn("t-in");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        expectInstalled(server, "t-out", 456);
        expectInstalled(server, "t-in", 123);
        byte[] body = OPENED.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/integrations/github/webhook") // Authorization 없음 = permitAll 확인
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", GitHubWebhookServiceTest.sign(SECRET, body))
                .header("X-GitHub-Event", "issues")
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isAccepted());

        verify(appTriggerFirer, timeout(5000)).fire(eq(inWorkflow), eq(TriggerType.GITHUB_NEW_ISSUE), anyMap());
        server.verify(Duration.ofSeconds(5)); // 비동기 디스패치가 두 소유자의 설치 범위 조회(요청 4개)를 다 쓸 때까지
        verify(appTriggerFirer, never()).fire(eq(outWorkflow), any(), any());
    }

    @Test
    @DisplayName("[Review Focus 7] permitAll은 webhook 한 경로뿐 — 같은 접두의 options는 여전히 인증 필요")
    void optionsStillRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/github/options/installed_repos"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }
}
