package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.service.GitHubTokenService;
import com.ieum.common.util.AesEncryptor;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * 토큰 갱신이 호출자 트랜잭션에 합류하지 않는지 — 실제 트랜잭션 매니저·H2·AES로 본다. 컨텍스트 구성은
 * {@code AppTriggerSubscriptionRepositoryTest}와 같다.
 */
@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class GitHubTokenRefreshTransactionTest {

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

    @Autowired private GitHubTokenService gitHubTokenService;
    @Autowired private ConnectedAccountRepository connectedAccountRepository;
    @Autowired private AesEncryptor aesEncryptor;
    @Autowired private RestTemplate restTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        connectedAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("[Review Focus 8] 호출자 트랜잭션이 롤백돼도(저장 검증 400·503) 회전된 GitHub 토큰은 커밋된다")
    void rotatedTokensSurviveCallerRollback() {
        UUID userId = UUID.randomUUID();
        connectedAccountRepository.save(ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.GITHUB)
            .accessToken(aesEncryptor.encrypt("old-access"))
            .refreshToken(aesEncryptor.encrypt("old-refresh"))
            .tokenExpiresAt(LocalDateTime.now().minusHours(1))
            .refreshTokenExpiresAt(LocalDateTime.now().plusDays(30))
            .build());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://github.com/login/oauth/access_token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""
                {"access_token": "new-access", "refresh_token": "new-refresh",
                 "expires_in": 28800, "refresh_token_expires_in": 15897600}""", MediaType.APPLICATION_JSON));

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(gitHubTokenService.getValidAccessToken(userId)).isEqualTo("new-access");
            status.setRollbackOnly(); // sync가 검증 거부·GitHub 장애로 저장 전체를 롤백하는 경우
        });

        server.verify();
        ConnectedAccount saved = connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.GITHUB).orElseThrow();
        assertThat(aesEncryptor.decrypt(saved.getRefreshToken())).isEqualTo("new-refresh");
        assertThat(aesEncryptor.decrypt(saved.getAccessToken())).isEqualTo("new-access");
    }
}
