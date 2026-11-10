package com.ieum.api.trigger.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GmailMessageNormalizerTest {

    private final ObjectMapper json = new ObjectMapper();

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode message(String payloadJson) throws Exception {
        return json.readTree("""
            {"id":"m1","threadId":"th1","snippet":"미리보기","internalDate":"1790000000123",
             "labelIds":["INBOX","UNREAD"],"payload":%s}""".formatted(payloadJson));
    }

    private static final String HEADERS = """
        [{"name":"From","value":"보스 <boss@x.com>"},{"name":"To","value":"me@x.com"},
         {"name":"Subject","value":"회의"},{"name":"subject","value":"중복은 무시"}]""";

    @Test
    @DisplayName("필드 9개 고정 순서, receivedAt은 ISO-8601 UTC, labels는 라벨 id")
    void normalizesFixedFields() throws Exception {
        Map<String, Object> out = GmailMessageNormalizer.normalize(message("""
            {"mimeType":"text/plain","headers":%s,"body":{"data":"%s"}}""".formatted(HEADERS, b64("본문"))));

        assertThat(out.keySet()).containsExactly(
            "messageId", "threadId", "from", "to", "subject", "snippet", "bodyText", "receivedAt", "labels");
        assertThat(out).containsEntry("messageId", "m1").containsEntry("threadId", "th1")
            .containsEntry("from", "보스 <boss@x.com>").containsEntry("to", "me@x.com")
            .containsEntry("subject", "회의").containsEntry("snippet", "미리보기")
            .containsEntry("bodyText", "본문").containsEntry("receivedAt", "2026-09-21T14:13:20.123Z")
            .containsEntry("labels", List.of("INBOX", "UNREAD"));
    }

    @Test
    @DisplayName("multipart — text/plain 파트가 html보다 우선(중첩 파트까지)")
    void prefersPlainPart() throws Exception {
        Map<String, Object> out = GmailMessageNormalizer.normalize(message("""
            {"mimeType":"multipart/mixed","headers":%s,"parts":[
              {"mimeType":"multipart/alternative","parts":[
                {"mimeType":"text/html","body":{"data":"%s"}},
                {"mimeType":"text/plain","body":{"data":"%s"}}]}]}""".formatted(HEADERS, b64("<b>HTML</b>"), b64("평문"))));

        assertThat(out.get("bodyText")).isEqualTo("평문");
    }

    @Test
    @DisplayName("html만 있으면 태그·script·style 제거, 엔티티 일부 해제, 공백 정리")
    void stripsHtmlWhenNoPlain() throws Exception {
        String html = "<html><style>p{}</style><script>x()</script><p>안녕&nbsp;&amp;</p><br>  반가워</html>";
        Map<String, Object> out = GmailMessageNormalizer.normalize(message("""
            {"mimeType":"text/html","headers":%s,"body":{"data":"%s"}}""".formatted(HEADERS, b64(html))));

        assertThat(out.get("bodyText")).isEqualTo("안녕 & 반가워");
    }

    @Test
    @DisplayName("닫히지 않은 <style> 40,000개 HTML도 1초 안에 정규화된다 — 폴링 스레드가 메일 한 통에 멈추지 않게")
    void unclosedStylesAreLinear() {
        String html = "<style>".repeat(40_000);

        String text = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> GmailMessageNormalizer.stripHtml(html));

        assertThat(text).isEmpty();
    }

    @Test
    @DisplayName("닫는 태그 없는 script 뒤는 끝까지 버리고, 대소문자 무시")
    void unclosedScriptDropsRest() {
        assertThat(GmailMessageNormalizer.stripHtml("앞<SCRIPT a=1>x()</Script>중간<style>p{} 뒤"))
            .isEqualTo("앞 중간");
    }

    @Test
    @DisplayName("agent와 공유하는 스캐너 케이스 — 같은 입력, 같은 기대값")
    void sharedScannerCases() {
        assertThat(GmailMessageNormalizer.stripHtml("<p>안녕</p><style>p{}</style>&amp;반가워")).isEqualTo("안녕 &반가워");
        assertThat(GmailMessageNormalizer.stripHtml("a<style>p{}</style >b")).isEqualTo("a b");
        assertThat(GmailMessageNormalizer.stripHtml("a<styles>b</styles>c")).isEqualTo("a b c");
        assertThat(GmailMessageNormalizer.stripHtml("a<STYLE>x</STYLE>b")).isEqualTo("a b");
        assertThat(GmailMessageNormalizer.stripHtml("a<!--[if mso]>x<![endif]-->b")).isEqualTo("a x b");
        assertThat(GmailMessageNormalizer.stripHtml("a<![foo[x]]>b")).isEqualTo("a b");
        assertThat(GmailMessageNormalizer.stripHtml("a&rsquo;b")).isEqualTo("a&rsquo;b");
    }

    @Test
    @DisplayName("200KB 넘는 적대 입력도 각각 1초 안에 끝난다")
    void hostileInputsAreLinear() {
        for (String html : List.of("<style>".repeat(40_000), "<a ".repeat(66_667), "<!--".repeat(50_000),
            "a<b".repeat(66_667), "<".repeat(80_000))) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> GmailMessageNormalizer.stripHtml(html));
        }
    }

    @Test
    @DisplayName("200,000자 넘는 HTML은 앞 200,000자만 읽는다")
    void capsHtmlInput() {
        String text = GmailMessageNormalizer.stripHtml("가".repeat(200_000) + "나");

        assertThat(text).hasSize(200_000).doesNotContain("나");
    }

    @Test
    @DisplayName("깨진 base64 본문 파트는 건너뛴다 — 던지면 스윕이 그 메일에서 멈춘다")
    void brokenBodyDataIsSkipped() throws Exception {
        Map<String, Object> out = GmailMessageNormalizer.normalize(message("""
            {"mimeType":"multipart/alternative","headers":%s,"parts":[
              {"mimeType":"text/plain","body":{"data":"!!!"}},
              {"mimeType":"text/html","body":{"data":"%s"}}]}""".formatted(HEADERS, b64("<p>대체</p>"))));

        assertThat(out.get("bodyText")).isEqualTo("대체");
    }

    @Test
    @DisplayName("본문은 10,000자에서 자른다")
    void truncatesBody() throws Exception {
        Map<String, Object> out = GmailMessageNormalizer.normalize(message("""
            {"mimeType":"text/plain","headers":%s,"body":{"data":"%s"}}""".formatted(HEADERS, b64("가".repeat(12_000)))));

        assertThat((String) out.get("bodyText")).hasSize(GmailMessageNormalizer.BODY_TEXT_MAX);
    }

    @Test
    @DisplayName("헤더·본문·라벨이 없어도 빈 값으로 모양을 지킨다")
    void missingPartsKeepShape() throws Exception {
        Map<String, Object> out = GmailMessageNormalizer.normalize(json.readTree(
            "{\"id\":\"m2\",\"threadId\":\"t\",\"internalDate\":\"0\",\"payload\":{}}"));

        assertThat(out).containsEntry("from", "").containsEntry("subject", "").containsEntry("bodyText", "")
            .containsEntry("snippet", "").containsEntry("labels", List.of());
        assertThat(GmailMessageNormalizer.internalDateSec(json.readTree("{\"internalDate\":\"1790000000999\"}")))
            .isEqualTo(1_790_000_000L);
    }
}
