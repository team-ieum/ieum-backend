package com.ieum.auth.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.auth.delegation.DelegationTokenService.Delegation;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DelegationTokenServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DelegationTokenService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new DelegationTokenService(redisTemplate);
    }

    @Test
    @DisplayName("발급 — 원문은 32바이트 base64url(43자), Redis엔 sha256 해시 키에 {userId, role, workflowId}를 600초로")
    void issue_storesHashedKeyWithTtl() throws Exception {
        String token = service.issue(userId, "ROLE_USER", workflowId);

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), value.capture(), eq(Duration.ofSeconds(600)));
        assertThat(key.getValue()).isEqualTo(DelegationTokenService.key(token)).doesNotContain(token);
        JsonNode json = new ObjectMapper().readTree(value.getValue());
        assertThat(json.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(json.get("role").asText()).isEqualTo("ROLE_USER");
        assertThat(json.get("workflowId").asText()).isEqualTo(workflowId.toString());
        assertThat(value.getValue()).doesNotContain(token);
    }

    @Test
    @DisplayName("발급할 때마다 다른 원문")
    void issue_isRandom() {
        assertThat(service.issue(userId, "ROLE_USER", workflowId))
            .isNotEqualTo(service.issue(userId, "ROLE_USER", workflowId));
    }

    @Test
    @DisplayName("키는 delegation:{sha256 hex(원문)} — 계약 고정값")
    void key_isSha256Hex() {
        assertThat(DelegationTokenService.key("abc"))
            .isEqualTo("delegation:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("조회 — 저장된 JSON을 그대로 돌려준다")
    void resolve_returnsDelegation() {
        when(valueOperations.get(DelegationTokenService.key("tok"))).thenReturn(
            "{\"userId\":\"" + userId + "\",\"role\":\"ROLE_TESTER\",\"workflowId\":\"" + workflowId + "\"}");

        assertThat(service.resolve("tok")).contains(new Delegation(userId, "ROLE_TESTER", workflowId));
    }

    @Test
    @DisplayName("조회 — 없는 키(만료·폐기·위조)와 깨진 값은 empty")
    void resolve_missingOrBroken_isEmpty() {
        when(valueOperations.get(anyString())).thenReturn(null);
        assertThat(service.resolve("tok")).isEmpty();

        when(valueOperations.get(anyString())).thenReturn("{\"userId\":\"not-a-uuid\"}");
        assertThat(service.resolve("tok")).isEmpty();
    }

    @Test
    @DisplayName("폐기 — 해시 키를 지운다, null이면 Redis를 건드리지 않는다")
    void revoke_deletesHashedKey() {
        service.revoke("tok");
        verify(redisTemplate).delete(DelegationTokenService.key("tok"));

        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        new DelegationTokenService(untouched).revoke(null);
        verifyNoInteractions(untouched);
    }
}
