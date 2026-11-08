package com.ieum.workflowcore.engine.executor;

import static com.ieum.workflowcore.engine.executor.FormatterInputs.choice;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.flag;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.positiveInt;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.required;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.text;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.timed;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/** 텍스트 포맷터 12개. 정규식 매칭은 전부 {@link FormatterInputs#timed} 위에서 돈다. */
final class TextFormats {

    /**
     * 앞 경계 + 소유격 수량자 — 없으면 {@code "a".repeat(90_000)}에서 시작 위치마다 다시 훑어 O(N²)이다.
     * 도메인은 그룹 반복 대신 문자 클래스 + 뒤 확인({@code .tld}로 끝남) — 그룹 반복은 Java에서 반복마다 재귀라 긴 입력에 StackOverflow.
     */
    private static final Pattern EMAIL = Pattern.compile(
        "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-][A-Za-z0-9.-]*(?<=\\.[A-Za-z]{2,63})");
    private static final String AREA = "(?:2|1[016789]|[3-6][1-5]|70|80|50\\d?)";
    /** 앞뒤가 숫자·{@code 숫자-}로 이어지면(카드·계좌번호 블록) 뽑지 않는다. */
    private static final Pattern PHONE = Pattern.compile(
        "(?<!\\d)(?<!\\d-)(?:(?:\\+82[- ]?0?|0)" + AREA + "(?:\\) ?|[- .])?\\d{3,4}[- .]?\\d{4}"
            + "|1[5-9]\\d{2}-\\d{4})(?!\\d)(?!-\\d)");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String URL_TRAILING = ".,!?;:";

    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();
    private static final Document.OutputSettings COMPACT = new Document.OutputSettings().prettyPrint(false);

    private TextFormats() {
    }

    private static Map<String, Object> out(Object value) {
        return Map.of("output", value);
    }

    static Map<String, Object> textCase(Map<String, Object> in, ZonedDateTime now) {
        String s = text(in, "input");
        return out(switch (choice(in, "mode", "title", Set.of("upper", "lower", "capitalize", "title"))) {
            case "upper" -> s.toUpperCase(Locale.ROOT);
            case "lower" -> s.toLowerCase(Locale.ROOT);
            case "capitalize" -> s.isEmpty() ? s
                : new StringBuilder().appendCodePoint(Character.toUpperCase(s.codePointAt(0)))
                    .append(s, Character.charCount(s.codePointAt(0)), s.length()).toString();
            default -> titleCase(s);
        });
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean wordStart = true;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(wordStart ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
            wordStart = Character.isWhitespace(cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    static Map<String, Object> trim(Map<String, Object> in, ZonedDateTime now) {
        return out(text(in, "input").strip());
    }

    static Map<String, Object> replace(Map<String, Object> in, ZonedDateTime now) {
        String find = notation(required(in, "find", "찾을 글자"));
        String replacement = notation(text(in, "replace"));
        String s = text(in, "input");
        // 결과 길이를 먼저 계산한다 — 만들고 나서 재면 수십억 자 문자열로 OOM이 난다.
        long count = 0;
        for (int i = s.indexOf(find); i >= 0; i = s.indexOf(find, i + find.length())) {
            count++;
        }
        if (s.length() + count * (replacement.length() - find.length()) > FormatterInputs.MAX_TEXT) {
            throw new FormatterFailure("바꾼 결과가 10만 자를 넘어요");
        }
        return out(s.replace(find, replacement));
    }

    private static String notation(String s) {
        return s.replace("[:space:]", " ").replace("[:newline:]", "\n").replace("[:tab:]", "\t");
    }

    static Map<String, Object> truncate(Map<String, Object> in, ZonedDateTime now) {
        int max = positiveInt(in, "maxLength", "최대 글자 수");
        String s = text(in, "input");
        List<Integer> bounds = graphemeBounds(s);
        int count = bounds.size() - 1;
        if (count <= max) {
            return out(s);
        }
        return flag(in, "ellipsis")
            ? out(s.substring(0, bounds.get(max - 1)) + "…")
            : out(s.substring(0, bounds.get(max)));
    }

    /** grapheme 경계 오프셋(0 포함, 끝 포함). 크기 - 1 = 사용자가 보는 글자 수. */
    private static List<Integer> graphemeBounds(String s) {
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        List<Integer> bounds = new ArrayList<>();
        for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) {
            bounds.add(b);
        }
        return bounds;
    }

    static Map<String, Object> split(Map<String, Object> in, ZonedDateTime now) {
        String part = choice(in, "part", "first", Set.of("first", "second", "last", "all"));
        String sep = text(in, "separator");
        if ("all".equals(part) && text(in, "input").isEmpty()) {
            return out(List.of());
        }
        String[] raw = Pattern.compile(Pattern.quote(sep.isEmpty() ? "," : sep)).split(timed(text(in, "input")), -1);
        List<Object> pieces = new ArrayList<>(raw.length);
        for (String p : raw) {
            pieces.add(p.strip());
        }
        return out(switch (part) {
            case "all" -> capped(pieces);
            case "second" -> pieces.size() > 1 ? pieces.get(1) : "";
            case "last" -> pieces.get(pieces.size() - 1);
            default -> pieces.get(0);
        });
    }

    static Map<String, Object> defaultValue(Map<String, Object> in, ZonedDateTime now) {
        String dflt = required(in, "defaultValue", "기본값");
        String s = text(in, "input");
        return out(ConditionValues.isEmpty(s) ? dflt : s);
    }

    static Map<String, Object> extract(Map<String, Object> in, ZonedDateTime now) {
        Pattern p = switch (choice(in, "kind", null, Set.of("email", "phone", "url", "number"))) {
            case "email" -> EMAIL;
            case "phone" -> PHONE;
            case "url" -> URL;
            default -> NUMBER;
        };
        return matches(p, text(in, "input"), flag(in, "all"), p == URL);
    }

    static Map<String, Object> extractPattern(Map<String, Object> in, ZonedDateTime now) {
        String pattern = required(in, "pattern", "정규식 패턴");
        if (pattern.length() > FormatterInputs.MAX_PATTERN) {
            throw new FormatterFailure("정규식 패턴이 200자를 넘어요");
        }
        Pattern p;
        try {
            p = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new FormatterFailure("정규식 패턴 오류예요: " + e.getDescription());
        }
        return matches(p, text(in, "input"), flag(in, "all"), false);
    }

    private static Map<String, Object> matches(Pattern p, String s, boolean all, boolean trimUrl) {
        Matcher m = p.matcher(timed(s));
        List<Object> found = new ArrayList<>();
        while (m.find()) {
            String g = m.group();
            if (trimUrl) {
                g = trimUrl(g);
                if (g == null) {
                    continue;
                }
            }
            if (!all) {
                return out(g);
            }
            found.add(g);
            capped(found);
        }
        return out(all ? found : "");
    }

    /**
     * 닫는 괄호 뒤에 ASCII 밖 글자(한글 조사 등)가 붙으면 괄호 뒤를 버리고, 끝 문장부호와 짝 없는 {@code )}·{@code ]}를 뗀다.
     * 호스트가 없으면 null.
     */
    private static String trimUrl(String g) {
        for (int i = 0; i + 1 < g.length(); i++) {
            char c = g.charAt(i);
            if ((c == ')' || c == ']') && g.charAt(i + 1) > 0x7F) {
                g = g.substring(0, i + 1);
                break;
            }
        }
        int end = g.length();
        while (end > 0) {
            char c = g.charAt(end - 1);
            String head = g.substring(0, end);
            if (URL_TRAILING.indexOf(c) >= 0
                || c == ')' && unbalanced(head, '(', ')')
                || c == ']' && unbalanced(head, '[', ']')) {
                end--;
            } else {
                break;
            }
        }
        g = g.substring(0, end);
        String rest = g.substring(g.indexOf("://") + 3);
        return rest.isEmpty() || !Character.isLetterOrDigit(rest.charAt(0)) ? null : g;
    }

    private static boolean unbalanced(String s, char open, char close) {
        return s.chars().filter(ch -> ch == close).count() > s.chars().filter(ch -> ch == open).count();
    }

    private static List<Object> capped(List<Object> items) {
        if (items.size() > FormatterInputs.MAX_ITEMS) {
            throw new FormatterFailure("결과 목록이 1만 개를 넘어요");
        }
        return items;
    }

    static Map<String, Object> stripHtml(Map<String, Object> in, ZonedDateTime now) {
        Document doc = Jsoup.parse(text(in, "input"));
        doc.select("script, style").remove();
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(org.jsoup.nodes.Node node, int depth) {
                if (node instanceof TextNode t) {
                    sb.append(t.text());
                } else if (node instanceof Element e && (e.isBlock() || e.nameIs("br"))) {
                    sb.append('\n');
                }
            }

            @Override
            public void tail(org.jsoup.nodes.Node node, int depth) {
                if (node instanceof Element e && e.isBlock()) {
                    sb.append('\n');
                }
            }
        }, doc.body());
        List<String> lines = new ArrayList<>();
        for (String line : sb.toString().split("\n")) {
            String l = line.strip();
            if (!l.isEmpty()) {
                lines.add(l);
            }
        }
        return out(String.join("\n", lines));
    }

    static Map<String, Object> markdownToHtml(Map<String, Object> in, ZonedDateTime now) {
        String html = HTML.render(MARKDOWN.parse(text(in, "input")));
        return out(Jsoup.clean(html, "", Safelist.relaxed(), COMPACT).strip());
    }

    static Map<String, Object> urlEncode(Map<String, Object> in, ZonedDateTime now) {
        String s = text(in, "input");
        if ("encode".equals(choice(in, "direction", "encode", Set.of("encode", "decode")))) {
            return out(URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        try {
            return out(URLDecoder.decode(s, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new FormatterFailure("URL 인코딩을 풀지 못했어요");
        }
    }

    static Map<String, Object> length(Map<String, Object> in, ZonedDateTime now) {
        String s = text(in, "input");
        if ("words".equals(choice(in, "unit", "chars", Set.of("chars", "words")))) {
            String t = s.strip();
            return out(t.isEmpty() ? 0L : (long) WHITESPACE.split(timed(t)).length);
        }
        return out((long) graphemeBounds(s).size() - 1);
    }
}
