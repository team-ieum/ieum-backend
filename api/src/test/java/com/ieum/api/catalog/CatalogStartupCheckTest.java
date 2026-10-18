package com.ieum.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.TriggerHandler;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 카탈로그 기동 검사 (IEUM-BE-93). 실제 OptionSource 빈 키와의 대조는 컨텍스트 테스트(IeumApplicationTest.contextLoads)가
 * 맡는다 — 검사가 생성자에서 던지므로 키가 빠지면 컨텍스트가 뜨지 않는다.
 */
class CatalogStartupCheckTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private static final Set<TriggerType> ALL = EnumSet.allOf(TriggerType.class);
    private static final String MANUAL_APP =
            "\"manual\":{\"provider\":\"ieum\",\"title\":\"수동\",\"brand\":\"webhook\",\"serviceType\":null,\"auth\":null}";
    private static final String TRIGGER = """
            {"node_type":"TRIGGER","kind":"trigger","trigger_type":"MANUAL","tool_key":null,
             "fixed":{"type":"TRIGGER","config":{}}}""";

    @Test
    @DisplayName("실제 카탈로그 + 전 TriggerType 핸들러 + 카탈로그 optionsSource 합집합 키 → 문제 없음(auth·tool_key 규칙 포함)")
    void realCatalog_noProblems() {
        assertThat(CatalogStartupCheck.problems(CATALOG, ALL, catalogSources(CATALOG))).isEmpty();
    }

    @Test
    @DisplayName("핸들러가 없는 TriggerType → '트리거 핸들러 없음'")
    void missingHandler() {
        Set<TriggerType> handled = EnumSet.complementOf(EnumSet.of(TriggerType.GITHUB_NEW_ISSUE));

        assertThat(CatalogStartupCheck.problems(CATALOG, handled, catalogSources(CATALOG)))
                .containsExactly("트리거 핸들러 없음: GITHUB_NEW_ISSUE");
    }

    @Test
    @DisplayName("구독형 핸들러가 노드 테스트 방식을 RUN(기본값)으로 두면 문제 — LISTEN·FETCH면 통과")
    void subscribingHandlerMustNotRunNodeTest() {
        TriggerHandler runDefault = new TriggerHandler() {
            @Override
            public TriggerType type() {
                return TriggerType.GMAIL_NEW_EMAIL;
            }

            @Override
            public boolean subscribes() {
                return true;
            }
        };
        TriggerHandler fetch = new TriggerHandler() {
            @Override
            public TriggerType type() {
                return TriggerType.GITHUB_NEW_ISSUE;
            }

            @Override
            public boolean subscribes() {
                return true;
            }

            @Override
            public NodeTestMode nodeTestMode() {
                return NodeTestMode.FETCH;
            }
        };

        assertThat(CatalogStartupCheck.handlerProblems(new TriggerHandlerRegistry(List.of(runDefault, fetch))))
                .containsExactly("구독형 트리거의 노드 테스트 방식이 RUN: GMAIL_NEW_EMAIL");
    }

    @Test
    @DisplayName("문제가 있으면 생성자가 IllegalStateException으로 컨텍스트 로드를 막는다(fail-closed)")
    void constructor_throwsOnProblems() {
        assertThatThrownBy(() -> new CatalogStartupCheck(CATALOG, new TriggerHandlerRegistry(List.of()), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("카탈로그 기동 검사 실패");
    }

    @Test
    @DisplayName("실제 카탈로그에서 optionsSource 키 하나를 빼면 그 키를 쓰는 이벤트·프리셋마다 문제 하나")
    void realCatalog_missingOptionKey() {
        Set<String> keys = catalogSources(CATALOG);
        String removed = keys.iterator().next();
        keys.remove(removed);
        long owners = CATALOG.events().stream().filter(e -> e.optionsSources().contains(removed)).count()
                + CATALOG.presets().stream().filter(p -> p.optionsSources().contains(removed)).count();

        List<String> problems = CatalogStartupCheck.problems(CATALOG, ALL, keys);

        assertThat(problems).hasSize((int) owners)
                .allMatch(p -> p.startsWith("optionsSource 공급원 없음: " + removed + " ("));
    }

    @Test
    @DisplayName("이벤트·프리셋의 공급원 없는 optionsSource → 키와 이벤트 이름·프리셋 id")
    void fixture_missingOptionKey(@TempDir Path dir) throws IOException {
        EventCatalog catalog = load(dir, MANUAL_APP,
                """
                [{"id":"ai.x","inputFields":[{"key":"s","optionsSource":"nope.preset"}]}]""",
                Map.of("manual/run.json", """
                        {"node_type":"TRIGGER","kind":"trigger","trigger_type":"MANUAL","tool_key":null,
                         "fixed":{"type":"TRIGGER","config":{}},
                         "inputFields":[{"key":"t","optionsSource":"nope.event"}]}"""));

        assertThat(CatalogStartupCheck.problems(catalog, ALL, Set.of())).containsExactly(
                "optionsSource 공급원 없음: nope.event (manual.run)",
                "optionsSource 공급원 없음: nope.preset (ai.x)");
    }

    @Test
    @DisplayName("apps.json auth가 null도 알려진 값도 아니면 → '알 수 없는 auth'")
    void fixture_unknownAuth(@TempDir Path dir) throws IOException {
        EventCatalog catalog = load(dir, MANUAL_APP + """
                ,"foo":{"provider":"foo","title":"Foo","brand":"foo","serviceType":"FOO","auth":"foo_token"}""",
                "[]", Map.of("manual/run.json", TRIGGER));

        assertThat(CatalogStartupCheck.problems(catalog, ALL, Set.of()))
                .containsExactly("알 수 없는 auth: foo_token (앱 foo)");
    }

    @Test
    @DisplayName("builder ACTION의 tool_key 규칙 인증 ≠ 앱 auth → 'tool_key 인증 불일치'")
    void fixture_toolKeyAuthMismatch(@TempDir Path dir) throws IOException {
        EventCatalog catalog = load(dir, gmailApp(), "[]", Map.of("gmail/send.json", action("builtin:notion_x", "")));

        assertThat(CatalogStartupCheck.problems(catalog, ALL, Set.of())).containsExactly(
                "tool_key 인증 불일치: gmail.send tool_key=builtin:notion_x 기대 auth=notion_token 앱 auth=google_oauth");
    }

    @Test
    @DisplayName("builder=false ACTION은 인증 대조에서 빠진다 — gmail.send_email(tool_key gmail) 같은 경우")
    void fixture_nonBuilderActionSkipped(@TempDir Path dir) throws IOException {
        EventCatalog catalog = load(dir, gmailApp(), "[]",
                Map.of("gmail/send.json", action("gmail", ",\"builder\":false")));

        assertThat(CatalogStartupCheck.problems(catalog, ALL, Set.of())).isEmpty();
    }

    private static String gmailApp() {
        return MANUAL_APP + """
                ,"gmail":{"provider":"google","title":"Gmail","brand":"gmail","serviceType":"GOOGLE","auth":"google_oauth"}""";
    }

    private static String action(String toolKey, String extra) {
        return """
                {"node_type":"ACTION","kind":"action","tool_key":"%s","fixed":{"type":"ACTION","config":{}}%s}"""
                .formatted(toolKey, extra);
    }

    private static Set<String> catalogSources(EventCatalog catalog) {
        Set<String> out = new TreeSet<>();
        catalog.events().stream().map(EventDefinition::optionsSources).forEach(out::addAll);
        catalog.presets().forEach(p -> out.addAll(p.optionsSources()));
        return out;
    }

    /** EventCatalogTest.writeCatalog와 같은 배치(catalog/events·index.txt·apps.json·presets.json)이고 apps·presets를 인자로 받는다 */
    private static EventCatalog load(Path dir, String appsBody, String presets, Map<String, String> events)
            throws IOException {
        Path root = dir.resolve("catalog");
        Map<String, String> sorted = new TreeMap<>(events);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            Path file = root.resolve("events").resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
        Files.writeString(root.resolve("index.txt"), String.join("\n", sorted.keySet()) + "\n");
        Files.writeString(root.resolve("apps.json"), "{" + appsBody + "}");
        Files.writeString(root.resolve("presets.json"), presets);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            return EventCatalog.load(loader);
        }
    }
}
