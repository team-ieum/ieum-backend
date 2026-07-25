package com.ieum.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.config.DefaultBetaPlatformProvider;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class IeumApplicationTest {

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

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
