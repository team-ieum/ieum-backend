package com.ieum.api.webhook.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 토큰 주소 웹훅의 원문 요청을 읽는다(IEUM-BE-91 R4 — 원문+헤더 모양(R5)은 {@link HookRequest#output}).
 * JSON 객체는 그대로, 배열은 {@code items}, 스칼라는 {@code value}, form은 키→값(같은 키 여러 번이면 리스트),
 * 그 밖의 타입은 {@code {"value": 원문}}. 쿼리는 합치되 본문 키가 우선한다. GET이거나 본문이 없으면 쿼리만.
 *
 * <p>쿼리는 {@code getParameter}가 아니라 {@code getQueryString}을 직접 푼다 — {@code getParameter}는
 * form 본문까지 소비해 이후 입력 스트림이 비게 된다.
 */
@Component
@RequiredArgsConstructor
public class HookPayloadReader {

    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    /**
     * includeRaw 모드에서 값을 가리는 자격증명 헤더(소문자). 서명 헤더({@code x-hub-signature-256}·{@code stripe-signature}·
     * {@code x-slack-signature} 등)는 includeRaw의 존재 이유(서명 검증)라 넣지 않는다. workflow-core
     * {@code SensitiveDataMasker}는 부분 일치({@code token}·{@code secret})라 {@code x-gitlab-token} 같은 검증 헤더까지
     * 가리므로 쓰지 않는다.
     */
    private static final Set<String> MASKED_HEADERS = Set.of(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-api-key", "x-auth-token", "x-access-token", "x-csrf-token", "x-xsrf-token");

    private final ObjectMapper objectMapper;

    public HookRequest read(HttpServletRequest request) {
        Map<String, Object> query = parseForm(request.getQueryString(), StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>(query);
        byte[] body = "GET".equalsIgnoreCase(request.getMethod()) ? new byte[0] : readBody(request);
        if (body.length > 0) {
            result.putAll(parseBody(body, request.getContentType()));
        }
        return new HookRequest(request.getMethod(), headers(request), query, result, body);
    }

    /** 소문자 키, 여러 값은 {@code ", "}로 잇는다. 자격증명 헤더({@link #MASKED_HEADERS})는 가린다(R5). */
    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String key = name.toLowerCase(Locale.ROOT);
            headers.put(key, MASKED_HEADERS.contains(key)
                ? "***"
                : String.join(", ", Collections.list(request.getHeaders(name))));
        }
        return headers;
    }

    private static byte[] readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new CustomException(ErrorCode.WEBHOOK_PAYLOAD_TOO_LARGE);
        }
        try {
            // Content-Length가 없거나(chunked) 거짓이어도 5MB+1바이트까지만 읽는다
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new CustomException(ErrorCode.WEBHOOK_PAYLOAD_TOO_LARGE);
            }
            return body;
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다.");
        }
    }

    private Map<String, Object> parseBody(byte[] body, String contentType) {
        MediaType type = null;
        if (contentType != null && !contentType.isBlank()) {
            try {
                type = MediaType.parseMediaType(contentType);
            } catch (InvalidMediaTypeException e) {
                type = MediaType.TEXT_PLAIN; // 읽을 수 없는 Content-Type은 텍스트로 본다
            }
        }
        Charset charset = type != null && type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
        if (type == null || MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)) {
            return parseJson(body);
        }
        if (MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(type)) {
            return parseForm(new String(body, charset), charset);
        }
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("value", new String(body, charset));
        return text;
    }

    private Map<String, Object> parseJson(byte[] body) {
        JsonNode node;
        try {
            node = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (IOException e) {
            // 예외 메시지엔 본문 조각이 담기므로 응답·로그에 싣지 않는다
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 본문이 올바른 JSON이 아닙니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (node == null || node.isMissingNode()) {
            return result; // 공백뿐인 본문
        }
        Object value = objectMapper.convertValue(node, Object.class);
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
        } else {
            result.put(node.isArray() ? "items" : "value", value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseForm(String encoded, Charset charset) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            try {
                key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), charset);
                value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), charset);
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "URL 인코딩이 올바르지 않습니다.");
            }
            result.merge(key, value, (prev, next) -> {
                List<Object> list = prev instanceof List<?> l ? (List<Object>) l : new ArrayList<>(List.of(prev));
                list.add(next);
                return list;
            });
        }
        return result;
    }
}
