package com.ieum.api.integration.domain;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * "연결된 서비스 관리" 탭에 노출되는 연동 서비스 타입.
 *
 * <p>워크플로우 노드 정의(MongoDB {@code workflow_definitions})의 {@code nodes[].config.brand}
 * 값 중 대표 brand 하나와 대응한다(GOOGLE은 gmail·sheets도 함께 매칭). 이 enum은 외부 입력(path 변수)을 허용 brand로 제한하는 화이트리스트
 * 역할도 겸한다 — 임의 brand 값으로 조회하는 것을 차단한다.
 *
 * <p>{@code openai}(LLM/web_search), {@code webhook}(트리거 종류) 등 연동 서비스 관리 대상이
 * 아닌 brand 값은 의도적으로 제외한다.
 */
@Getter
public enum IntegrationServiceType {

    /** agent는 Google 도구 노드에 brand {@code google} 외에 {@code gmail}·{@code sheets}도 넣는다 */
    GOOGLE("google", "gmail", "sheets"),
    NOTION("notion"),
    GITHUB("github"),
    SLACK("slack"),
    DISCORD("discord");

    /** 노드 config.brand에 저장되는 대표 식별자 */
    private final String brand;

    /** 이 서비스로 조회할 때 매칭하는 모든 brand — 대표 brand가 첫 번째다 */
    private final List<String> brands;

    IntegrationServiceType(String brand, String... aliases) {
        this.brand = brand;
        this.brands = Stream.concat(Stream.of(brand), Stream.of(aliases)).toList();
    }

    /**
     * path 변수 문자열을 enum으로 변환한다. 대소문자를 무시하며, 지원하지 않는 값이면
     * {@link ErrorCode#UNSUPPORTED_SERVICE_TYPE} 예외를 던진다.
     */
    public static IntegrationServiceType from(String value) {
        if (value == null || value.isBlank()) {
            throw new CustomException(ErrorCode.UNSUPPORTED_SERVICE_TYPE);
        }
        try {
            return IntegrationServiceType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNSUPPORTED_SERVICE_TYPE,
                "지원하지 않는 연동 서비스 타입입니다: " + value);
        }
    }
}
