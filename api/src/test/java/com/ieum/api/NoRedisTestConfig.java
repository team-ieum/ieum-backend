package com.ieum.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 테스트엔 Redis가 없다. 연결 시도 자체를 실패시켜 기동 경로가 "Redis 장애" 분기를 타게 한다
 * — {@code ExecutionJobQueueBootstrap}이 잡 큐를 안 띄우고 @Async 폴백으로 남는지까지 함께 확인된다.
 * ({@code @MockitoBean}은 컨텍스트 로드 뒤에 주입돼서 부팅 중 호출을 못 잡는다.)
 */
@TestConfiguration
public class NoRedisTestConfig {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
        RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
        org.mockito.BDDMockito.given(factory.getConnection())
            .willThrow(new RedisConnectionFailureException("테스트 환경엔 Redis가 없다"));
        return factory;
    }
}
