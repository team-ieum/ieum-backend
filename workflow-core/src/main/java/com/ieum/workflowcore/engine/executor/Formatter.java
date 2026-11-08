package com.ieum.workflowcore.engine.executor;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.function.BiFunction;

/** {@code formatter.*} 이벤트 → (title, 변환) 표. 변환은 런타임이 넘긴 {@code input}만 읽는다. */
final class Formatter {

    private record Op(String title, BiFunction<Map<String, Object>, ZonedDateTime, Map<String, Object>> fn) {
    }

    private static final Map<String, Op> OPS = Map.ofEntries(
        Map.entry("formatter.text_case", new Op("대소문자 바꾸기", TextFormats::textCase)),
        Map.entry("formatter.text_trim", new Op("공백 정리", TextFormats::trim)),
        Map.entry("formatter.text_replace", new Op("찾아 바꾸기", TextFormats::replace)),
        Map.entry("formatter.text_truncate", new Op("길이 자르기", TextFormats::truncate)),
        Map.entry("formatter.text_split", new Op("텍스트 나누기", TextFormats::split)),
        Map.entry("formatter.text_default", new Op("기본값 넣기", TextFormats::defaultValue)),
        Map.entry("formatter.text_extract", new Op("이메일·전화·URL·숫자 뽑기", TextFormats::extract)),
        Map.entry("formatter.text_extract_pattern", new Op("패턴으로 뽑기", TextFormats::extractPattern)),
        Map.entry("formatter.text_strip_html", new Op("HTML 태그 없애기", TextFormats::stripHtml)),
        Map.entry("formatter.text_markdown_to_html", new Op("Markdown을 HTML로", TextFormats::markdownToHtml)),
        Map.entry("formatter.text_url_encode", new Op("URL 인코딩", TextFormats::urlEncode)),
        Map.entry("formatter.text_length", new Op("글자 수 세기", TextFormats::length)),
        Map.entry("formatter.number_math", new Op("계산하기", NumberFormats::math)),
        Map.entry("formatter.number_format", new Op("숫자 표기", NumberFormats::format)),
        Map.entry("formatter.number_currency", new Op("금액 표기", NumberFormats::currency)),
        Map.entry("formatter.number_phone", new Op("전화번호 표기", NumberFormats::phone)));

    private Formatter() {
    }

    static boolean supports(String event) {
        return OPS.containsKey(event);
    }

    static String title(String event) {
        Op op = OPS.get(event);
        return op == null ? null : op.title();
    }

    /** 실패는 {@code "<title>: <이유> (입력: '<요약>')"} 메시지의 {@link FormatterFailure}. */
    static Map<String, Object> apply(String event, Map<String, Object> input, ZonedDateTime now) {
        Op op = OPS.get(event);
        if (op == null) {
            throw new FormatterFailure("지원하지 않는 포맷터예요: " + event);
        }
        try {
            return op.fn().apply(input, now);
        } catch (FormatterFailure e) {
            throw new FormatterFailure(op.title() + ": " + e.getMessage()
                + " (입력: '" + FormatterInputs.summary(input.get("input")) + "')");
        }
    }
}
