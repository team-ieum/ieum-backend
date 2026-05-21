package com.ieum.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class IeumApplicationTest {

    @Test
    @DisplayName("스프링 부트 애플리케이션 컨텍스트가 정상적으로 로드된다")
    void contextLoads() {
    }
}
