package com.ieum.workflowcore.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveDataMaskerTest {

    @Test
    @DisplayName("null 입력은 null을 반환한다")
    void mask_nullInput_returnsNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }

    @Test
    @DisplayName("민감 키(apiKey, api_key, token, secret, password, Authorization)는 ***로 마스킹된다")
    void mask_sensitiveKeys_areMasked() {
        Map<String, Object> data = new HashMap<>();
        data.put("apiKey", "sk-xxx");
        data.put("api_key", "sk-yyy");
        data.put("token", "raw-token");
        data.put("secret", "raw-secret");
        data.put("password", "raw-password");
        data.put("Authorization", "Bearer raw");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked.values()).containsOnly("***");
    }

    @Test
    @DisplayName("민감 키가 아닌 값은 그대로 보존된다")
    void mask_nonSensitiveKeys_areUntouched() {
        Map<String, Object> data = Map.of("city", "Seoul", "userId", 42);

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("city", "Seoul").containsEntry("userId", 42);
    }

    @Test
    @DisplayName("중첩 Map 내부는 마스킹하지 않는다(최상위 키만 처리)")
    void mask_nestedMap_isNotRecursivelyMasked() {
        Map<String, Object> nested = Map.of("apiKey", "sk-nested");
        Map<String, Object> data = Map.of("auth", nested);

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked.get("auth")).isSameAs(nested);
    }

    @Test
    @DisplayName("키 매칭은 대소문자를 구분하지 않는다")
    void mask_keyMatching_isCaseInsensitive() {
        Map<String, Object> data = Map.of("APIKEY", "sk-xxx");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("APIKEY", "***");
    }
}
