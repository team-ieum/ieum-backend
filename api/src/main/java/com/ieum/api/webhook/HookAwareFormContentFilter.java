package com.ieum.api.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.stereotype.Component;

/**
 * Boot 기본 FormContentFilter를 대체한다(같은 타입 빈이 있으면 Boot가 물러난다). 기본 필터는 PUT·PATCH·DELETE form 본문을
 * 크기 제한 없이 다 읽어 버려, 토큰 주소 웹훅({@code HookPayloadReader})이 받을 본문이 비고 5MB 제한도 우회된다.
 * 그 경로만 건너뛰고 나머지는 기본 동작 그대로다.
 */
@Component
public class HookAwareFormContentFilter extends OrderedFormContentFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/hooks/");
    }
}
