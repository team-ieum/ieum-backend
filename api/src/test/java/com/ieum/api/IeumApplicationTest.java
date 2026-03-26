package com.ieum.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class IeumApplicationTest {

    @Test
    void contextLoads() {
    }
}
