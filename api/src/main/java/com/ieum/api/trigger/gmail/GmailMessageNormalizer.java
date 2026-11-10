package com.ieum.api.trigger.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gmail {@code messages.get(format=full)} 응답 → 트리거 출력(spec §2.3). 폴링 런타임과 노드 테스트가 이 함수 하나를 공유한다 —
 * 원본 응답은 넘기지 않는다.
 */
public final class GmailMessageNormalizer {

    public static final int BODY_TEXT_MAX = 10_000;
    static final int HTML_MAX = 200_000;

    private GmailMessageNormalizer() {
    }

    public static Map<String, Object> normalize(JsonNode message) {
        Map<String, String> headers = headers(message.path("payload"));
        List<String> labels = new ArrayList<>();
        message.path("labelIds").forEach(label -> labels.add(label.asText()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", message.path("id").asText(""));
        out.put("threadId", message.path("threadId").asText(""));
        out.put("from", headers.getOrDefault("from", ""));
        out.put("to", headers.getOrDefault("to", ""));
        out.put("subject", headers.getOrDefault("subject", ""));
        out.put("snippet", message.path("snippet").asText(""));
        out.put("bodyText", truncate(bodyText(message.path("payload"))));
        out.put("receivedAt", Instant.ofEpochMilli(message.path("internalDate").asLong(0)).toString());
        out.put("labels", labels);
        return out;
    }

    /** 수신 시각(초). Gmail {@code internalDate}는 epoch 밀리초 문자열이다. */
    public static long internalDateSec(JsonNode message) {
        return message.path("internalDate").asLong(0) / 1000;
    }

    /** 헤더 이름은 대소문자 무시, 같은 이름은 첫 번째만. */
    private static Map<String, String> headers(JsonNode payload) {
        Map<String, String> headers = new HashMap<>();
        payload.path("headers").forEach(h ->
            headers.putIfAbsent(h.path("name").asText("").toLowerCase(), h.path("value").asText("")));
        return headers;
    }

    private static String bodyText(JsonNode payload) {
        String plain = findPart(payload, "text/plain");
        if (plain != null) {
            return plain.strip();
        }
        String html = findPart(payload, "text/html");
        return html == null ? "" : stripHtml(html);
    }

    /** DFS 첫 번째 일치 파트의 본문. 본문 데이터가 없는 파트는 건너뛴다. */
    private static String findPart(JsonNode part, String mimeType) {
        if (mimeType.equals(part.path("mimeType").asText()) && part.path("body").hasNonNull("data")) {
            // ponytail: charset 헤더 무시(UTF-8 가정) — 깨진 비UTF-8 메일이 보고되면 Content-Type charset 파싱 추가
            try {
                return new String(Base64.getUrlDecoder().decode(part.path("body").path("data").asText()),
                    StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null; // 깨진 본문 파트는 건너뛴다 — 던지면 스윕이 매 폴링 그 메일에서 멈춘다
            }
        }
        for (JsonNode child : part.path("parts")) {
            String found = findPart(child, mimeType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ponytail: 한 번 훑는 태그 제거 + 엔티티 6종만 — 요약·분류 입력용 근사치. 정확한 렌더가 필요하면 jsoup 도입
    // 정규식 (script|style).*?</\1>은 닫히지 않은 태그마다 끝까지 다시 훑어 제곱 시간 — 외부 메일 한 통이 폴링 스레드를 멈춘다
    static String stripHtml(String html) {
        if (html.length() > HTML_MAX) {
            html = html.substring(0, HTML_MAX);
        }
        char[] chars = html.toCharArray();
        for (int k = 0; k < chars.length; k++) {
            chars[k] = Character.toLowerCase(chars[k]); // 문자 단위라 길이가 그대로 — 인덱스를 html과 공유
        }
        String lower = new String(chars);
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            int lt = html.indexOf('<', i);
            int gt = lt < 0 ? -1 : html.indexOf('>', lt + 1);
            if (gt < 0) { // 더 닫히는 태그가 없다 — 나머지는 글자
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, lt);
            if (gt == lt + 1) { // "<>"는 태그가 아니다
                out.append("<>");
                i = gt + 1;
                continue;
            }
            out.append(' ');
            String raw = lower.startsWith("script", lt + 1) ? "script" : lower.startsWith("style", lt + 1) ? "style" : null;
            if (raw == null) {
                i = gt + 1;
                continue;
            }
            int close = lower.indexOf("</" + raw + ">", gt + 1); // 한 번만 찾고 그 뒤로 건너뛴다 — 재검색 없음
            if (close < 0) {
                break; // 닫히지 않은 script·style — 끝까지 버린다
            }
            i = close + raw.length() + 3;
        }
        return out.toString()
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replaceAll("\\s+", " ")
            .strip();
    }

    private static String truncate(String text) {
        return text.length() <= BODY_TEXT_MAX ? text : text.substring(0, BODY_TEXT_MAX);
    }
}
