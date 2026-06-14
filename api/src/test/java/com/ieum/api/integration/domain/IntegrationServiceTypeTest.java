package com.ieum.api.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IntegrationServiceTypeTest {

    @ParameterizedTest
    @DisplayName("from()은 대소문자 무관하게 enum으로 변환한다")
    @CsvSource({
        "discord, DISCORD",
        "DISCORD, DISCORD",
        "Discord, DISCORD",
        "slack,   SLACK",
        "notion,  NOTION",
        "github,  GITHUB",
        "google,  GOOGLE"
    })
    void from_caseInsensitive(String input, IntegrationServiceType expected) {
        assertThat(IntegrationServiceType.from(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("각 서비스 타입은 노드 config.brand와 매핑되는 brand 값을 가진다")
    void brandMapping() {
        assertThat(IntegrationServiceType.DISCORD.getBrand()).isEqualTo("discord");
        assertThat(IntegrationServiceType.SLACK.getBrand()).isEqualTo("slack");
        assertThat(IntegrationServiceType.NOTION.getBrand()).isEqualTo("notion");
        assertThat(IntegrationServiceType.GITHUB.getBrand()).isEqualTo("github");
        assertThat(IntegrationServiceType.GOOGLE.getBrand()).isEqualTo("google");
    }

    @ParameterizedTest
    @DisplayName("지원하지 않는 값은 UNSUPPORTED_SERVICE_TYPE 예외를 던진다")
    @ValueSource(strings = {"twitter", "openai", "webhook", "unknown"})
    void from_unsupported(String input) {
        assertThatThrownBy(() -> IntegrationServiceType.from(input))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNSUPPORTED_SERVICE_TYPE);
    }

    @Test
    @DisplayName("null/빈 문자열은 UNSUPPORTED_SERVICE_TYPE 예외를 던진다")
    void from_nullOrBlank() {
        assertThatThrownBy(() -> IntegrationServiceType.from(null))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNSUPPORTED_SERVICE_TYPE);
        assertThatThrownBy(() -> IntegrationServiceType.from("  "))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNSUPPORTED_SERVICE_TYPE);
    }
}
