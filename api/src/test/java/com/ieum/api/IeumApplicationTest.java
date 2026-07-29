package com.ieum.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.config.DefaultBetaPlatformProvider;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class IeumApplicationTest {

    /**
     * 테스트엔 Redis가 없다. 연결 시도 자체를 실패시켜 기동 경로가 "Redis 장애" 분기를 타게 한다
     * — {@code ExecutionJobQueueBootstrap}이 잡 큐를 안 띄우고 @Async 폴백으로 남는지까지 함께 확인된다.
     * ({@code @MockitoBean}은 컨텍스트 로드 뒤에 주입돼서 부팅 중 호출을 못 잡는다.)
     */
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

    @Autowired
    private BetaPlatformProvider betaPlatformProvider;

    @Test
    @DisplayName("스프링 부트 애플리케이션 컨텍스트가 정상적으로 로드된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("BetaPlatformProvider는 단일 빈이며 DefaultBetaPlatformProvider가 항상 선택된다 (Stub에 가려지지 않음)")
    void betaPlatformProvider_singleBean_resolvesToDefault() {
        assertThat(betaPlatformProvider).isInstanceOf(DefaultBetaPlatformProvider.class);
    }
}
