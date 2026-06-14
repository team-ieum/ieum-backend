package com.ieum.api.integration.domain;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * "연결된 서비스 관리" 탭에 노출되는 연동 서비스 타입.
 *
 * <p>워크플로우 노드 정의(MongoDB {@code workflow_definitions})의 {@code nodes[].config.brand}
 * 값과 1:1로 매핑된다. 이 enum은 외부 입력(path 변수)을 허용 brand로 제한하는 화이트리스트
 * 역할도 겸한다 — 임의 brand 값으로 조회하는 것을 차단한다.
 *
 * <p>{@code openai}(LLM/web_search), {@code webhook}(트리거 종류) 등 연동 서비스 관리 대상이
 * 아닌 brand 값은 의도적으로 제외한다.
 */
@Getter
@RequiredArgsConstructor
public enum IntegrationServiceType {

    GOOGLE("google"),
    NOTION("notion"),
    GITHUB("github"),
    SLACK("slack"),
    DISCORD("discord");

    /** 노드 config.brand에 저장되는 식별자 */
    private final String brand;

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
