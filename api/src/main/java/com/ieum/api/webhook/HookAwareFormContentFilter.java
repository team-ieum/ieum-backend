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

    public static final String HOOK_PATH_PREFIX = "/api/v1/hooks/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isHookPath(request);
    }

    /** 토큰 주소 웹훅 경로인가. 이 필터와 오류 로그 마스킹이 같은 판정을 쓴다. */
    public static boolean isHookPath(HttpServletRequest request) {
        // 디코드·정규화된 servletPath+pathInfo로 본다(Security와 같은 기준) — getRequestURI()면 %68ooks 같은 인코딩 경로가 빠져나간다
        String pathInfo = request.getPathInfo();
        return (request.getServletPath() + (pathInfo == null ? "" : pathInfo)).startsWith(HOOK_PATH_PREFIX);
    }
}
