package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.WebhookTokenResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class LocalDateTimeUtcSerializerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("응답 LocalDateTime은 JVM 기본 시간대 기준 Instant 문자열(…Z)로 나간다")
    void localDateTimeIsWrittenAsUtcInstant() throws Exception {
        LocalDateTime issuedAt = LocalDateTime.of(2026, 11, 6, 2, 30, 1, 107_000_000);

        JsonNode json = objectMapper.readTree(
            objectMapper.writeValueAsString(new WebhookTokenResponse("t", "/p", issuedAt)));

        String value = json.get("issuedAt").asText();
        assertThat(value).endsWith("Z");
        assertThat(Instant.parse(value)).isEqualTo(issuedAt.atZone(ZoneId.systemDefault()).toInstant());
    }
}
