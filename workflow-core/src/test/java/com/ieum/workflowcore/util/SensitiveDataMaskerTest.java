package com.ieum.workflowcore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
    @DisplayName("키 매칭은 대소문자를 구분하지 않는다")
    void mask_keyMatching_isCaseInsensitive() {
        Map<String, Object> data = Map.of("APIKEY", "sk-xxx");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("APIKEY", "***");
    }

    @Test
    @DisplayName("중첩 Map 안쪽 민감 키(config.headers.Authorization)도 마스킹된다")
    @SuppressWarnings("unchecked")
    void mask_nestedMap_isRecursivelyMasked() {
        Map<String, Object> data = Map.of(
            "config", Map.of(
                "url", "https://api.example.com/v1/items",
                "headers", Map.of(
                    "Authorization", "Bearer secret-token",
                    "Content-Type", "application/json"
                )
            )
        );

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        Map<String, Object> config = (Map<String, Object>) masked.get("config");
        Map<String, Object> headers = (Map<String, Object>) config.get("headers");
        assertThat(headers).containsEntry("Authorization", "***");
        assertThat(headers).containsEntry("Content-Type", "application/json");
        assertThat(config).containsEntry("url", "https://api.example.com/v1/items");
    }

    @Test
    @DisplayName("List 원소 안쪽 민감 키(config.tools[].config.apiKey)도 마스킹된다")
    @SuppressWarnings("unchecked")
    void mask_insideList_isRecursivelyMasked() {
        Map<String, Object> data = Map.of(
            "config", Map.of(
                "tools", List.of(
                    Map.of("toolKey", "slack_send", "config", Map.of("apiKey", "xoxb-raw")),
                    Map.of("toolKey", "notion_query", "config", Map.of("databaseId", "db-1"))
                )
            )
        );

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        Map<String, Object> config = (Map<String, Object>) masked.get("config");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) config.get("tools");
        assertThat((Map<String, Object>) tools.get(0).get("config")).containsEntry("apiKey", "***");
        assertThat(tools.get(0)).containsEntry("toolKey", "slack_send");
        assertThat((Map<String, Object>) tools.get(1).get("config"))
            .containsEntry("databaseId", "db-1");
    }

    @Test
    @DisplayName("Slack 웹훅 URL은 서비스는 알아볼 수 있게 두고 토큰 구간만 가린다")
    void mask_slackWebhookUrl_masksTokenSegment() {
        Map<String, Object> data = Map.of(
            "webhook_url", "https://hooks.slack.com/services/T0000/B1111/aBcDeFgHiJkLmNoP");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("webhook_url", "https://hooks.slack.com/services/***");
    }

    @Test
    @DisplayName("Discord 웹훅 URL은 서비스는 알아볼 수 있게 두고 토큰 구간만 가린다")
    void mask_discordWebhookUrl_masksTokenSegment() {
        Map<String, Object> data = Map.of(
            "url", "https://discord.com/api/webhooks/123456789/xyzSECRETtoken");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("url", "https://discord.com/api/webhooks/***");
    }

    @Test
    @DisplayName("중첩 Map/List 안쪽 웹훅 URL도 마스킹된다")
    @SuppressWarnings("unchecked")
    void mask_nestedWebhookUrl_isMasked() {
        Map<String, Object> data = Map.of(
            "tools", List.of(
                Map.of("config", Map.of(
                    "webhookUrl", "https://hooks.slack.com/services/T1/B2/zzzTOKEN"))
            )
        );

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        List<Map<String, Object>> tools = (List<Map<String, Object>>) masked.get("tools");
        assertThat((Map<String, Object>) tools.get(0).get("config"))
            .containsEntry("webhookUrl", "https://hooks.slack.com/services/***");
    }

    @Test
    @DisplayName("웹훅이 아닌 평범한 URL은 그대로 남는다")
    void mask_normalUrl_isUntouched() {
        Map<String, Object> data = Map.of(
            "url", "https://api.slack.com/methods/chat.postMessage",
            "docsUrl", "https://discord.com/developers/docs/intro",
            "endpoint", "https://example.com/api/webhooks-guide");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("url", "https://api.slack.com/methods/chat.postMessage");
        assertThat(masked).containsEntry("docsUrl", "https://discord.com/developers/docs/intro");
        assertThat(masked).containsEntry("endpoint", "https://example.com/api/webhooks-guide");
    }

    @Test
    @DisplayName("입력 Map은 변형되지 않고 새 Map이 반환된다")
    @SuppressWarnings("unchecked")
    void mask_doesNotMutateInput() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("apiKey", "sk-nested");
        Map<String, Object> data = new HashMap<>();
        data.put("config", nested);
        data.put("token", "raw");

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(data).containsEntry("token", "raw");
        assertThat(nested).containsEntry("apiKey", "sk-nested");
        assertThat(masked).isNotSameAs(data);
        assertThat((Map<String, Object>) masked.get("config")).isNotSameAs(nested);
        assertThat((Map<String, Object>) masked.get("config")).containsEntry("apiKey", "***");
    }

    @Test
    @DisplayName("null 값·null 키가 섞여 있어도 예외를 던지지 않는다")
    void mask_nullValuesAndKeys_doNotThrow() {
        Map<String, Object> data = new HashMap<>();
        data.put("plain", null);
        data.put("apiKey", null);
        data.put(null, "value");
        List<Object> listWithNull = new ArrayList<>(Arrays.asList("a", null));
        data.put("items", listWithNull);

        Map<String, Object> masked = SensitiveDataMasker.mask(data);

        assertThat(masked).containsEntry("plain", null);
        assertThat(masked).containsEntry("apiKey", "***");
        assertThat((List<Object>) masked.get("items")).containsExactly("a", null);
    }

    @Test
    @DisplayName("깊게 중첩된 구조에서도 죽지 않고, 깊이 상한을 넘으면 ***로 닫는다")
    void mask_deeplyNested_doesNotBlowUp() {
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < 5000; i++) {
            Map<String, Object> child = new HashMap<>();
            cursor.put("child", child);
            cursor = child;
        }
        cursor.put("apiKey", "sk-deep");

        Map<String, Object> masked = SensitiveDataMasker.mask(root);

        assertThat(masked).isNotNull();
        Object node = masked;
        int depth = 0;
        while (node instanceof Map<?, ?> map && map.get("child") != null) {
            node = map.get("child");
            depth++;
        }
        assertThat(node).isEqualTo("***");
        assertThat(depth).isLessThan(5000);
    }

    @Test
    @DisplayName("자기 참조 구조(순환)에서도 예외 없이 반환된다")
    void mask_selfReferencingMap_doesNotThrow() {
        Map<String, Object> data = new HashMap<>();
        data.put("self", data);
        List<Object> list = new ArrayList<>();
        list.add(list);
        data.put("selfList", list);

        assertThatCode(() -> SensitiveDataMasker.mask(data)).doesNotThrowAnyException();
    }
}
