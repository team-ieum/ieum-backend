package com.ieum.api.webhook.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 토큰 주소 웹훅으로 받은 요청. 출력 모양은 트리거 노드 {@code config.includeRaw}로 갈린다(IEUM-BE-91 R4·R5).
 *
 * @param body R4 결과(본문 + 쿼리, 본문 키 우선)
 * @param headers 소문자 + {@code -}→{@code _} 키, 자격증명 헤더는 가린 값({@code HookPayloadReader.MASKED_HEADERS})
 * @param rawBody 읽은 원문 바이트(GET이면 빈 배열)
 */
public record HookRequest(
    String method, Map<String, String> headers, Map<String, Object> query, Map<String, Object> body, byte[] rawBody) {

    public Map<String, Object> output(boolean includeRaw) {
        if (!includeRaw) {
            return body;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("method", method);
        raw.put("headers", headers);
        raw.put("query", query);
        raw.put("body", body);
        raw.put("rawBody", new String(rawBody, StandardCharsets.UTF_8));
        return raw;
    }
}
