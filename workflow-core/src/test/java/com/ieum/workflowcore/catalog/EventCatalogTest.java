package com.ieum.workflowcore.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventCatalogTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();

    @Test
    @DisplayName("이벤트 수 = 클래스패스 catalog/index.txt 줄 수이고 비어 있지 않다(개수는 카탈로그에서 센다)")
    void load_eventsMatchIndex() throws IOException {
        long indexLines;
        try (InputStream in = EventCatalog.class.getClassLoader().getResourceAsStream("catalog/index.txt")) {
            assertThat(in).isNotNull();
            indexLines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank()).count();
        }

        assertThat(indexLines).isPositive();
        assertThat(CATALOG.events()).hasSize((int) indexLines);
        assertThat(CATALOG.apps()).isNotEmpty();
        assertThat(CATALOG.presets()).isNotEmpty();
    }

    @Test
    @DisplayName("TriggerType 값마다 트리거 이벤트가 정확히 하나 있다")
    void everyTriggerType_hasExactlyOneEvent() {
        for (TriggerType type : TriggerType.values()) {
            assertThat(CATALOG.events()).as(type.name())
                    .filteredOn(e -> e.triggerType() == type).hasSize(1);
        }
    }

    @Test
    @DisplayName("TRIGGER 이벤트만 triggerTypeOf 값이 있고, ACTION 이벤트는 모두 tool_key가 있다")
    void catalogWide_triggerAndActionShape() {
        for (EventDefinition e : CATALOG.events()) {
            assertThat(CATALOG.triggerTypeOf(e.name()).isPresent()).as(e.name())
                    .isEqualTo(e.nodeType() == NodeType.TRIGGER);
            if (e.nodeType() == NodeType.ACTION) {
                assertThat(e.toolKey()).as(e.name()).isNotBlank();
            }
            assertThat(CATALOG.app(e.app())).as(e.name()).isPresent();
        }
    }

    @Test
    @DisplayName("이벤트 이름은 경로 events/<앱>/<이벤트>.json에서 계산한다 — gmail.new_email")
    void gmailNewEmail_trigger() {
        EventDefinition e = CATALOG.event("gmail.new_email").orElseThrow();

        assertThat(e.app()).isEqualTo("gmail");
        assertThat(e.nodeType()).isEqualTo(NodeType.TRIGGER);
        assertThat(e.kind()).isEqualTo("trigger");
        assertThat(e.triggerType()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(e.toolKey()).isNull();
        assertThat(e.builder()).isTrue();
        assertThat(e.generation()).isFalse();
        assertThat(CATALOG.triggerTypeOf("gmail.new_email")).hasValue(TriggerType.GMAIL_NEW_EMAIL);
    }

    @Test
    @DisplayName("ACTION 이벤트는 tool_key를 갖고, fields의 optionsSource를 모은다 — github.create_issue")
    void githubCreateIssue_toolKeyAndOptionsSources() {
        EventDefinition e = CATALOG.event("github.create_issue").orElseThrow();

        assertThat(e.nodeType()).isEqualTo(NodeType.ACTION);
        assertThat(e.triggerType()).isNull();
        assertThat(e.toolKey()).isEqualTo("builtin:github_create_issue");
        assertThat(e.optionsSources()).containsExactlyInAnyOrder("github.owners", "github.repos");
        assertThat(CATALOG.triggerTypeOf("github.create_issue")).isEmpty();
    }

    @Test
    @DisplayName("builder:false 이벤트도 읽는다 — gmail.send_email")
    void gmailSendEmail_nonBuilder() {
        EventDefinition e = CATALOG.event("gmail.send_email").orElseThrow();

        assertThat(e.builder()).isFalse();
        assertThat(e.toolKey()).isEqualTo("gmail");
    }

    @Test
    @DisplayName("apps.json: 연동 앱은 serviceType·auth, 내장 앱은 null")
    void apps_fields() {
        AppDefinition gmail = CATALOG.app("gmail").orElseThrow();
        assertThat(gmail).isEqualTo(new AppDefinition("gmail", "google", "Gmail", "gmail", "GOOGLE", "google_oauth"));

        AppDefinition ai = CATALOG.app("ai").orElseThrow();
        assertThat(ai.serviceType()).isNull();
        assertThat(ai.auth()).isNull();
        assertThat(CATALOG.app(null)).isEmpty();
        assertThat(CATALOG.app("nope")).isEmpty();
    }

    @Test
    @DisplayName("프리셋 optionsSource를 inputFields에서도 모은다 — ai.mcp")
    void presets_optionsSources() {
        PresetDefinition mcp = CATALOG.presets().stream()
                .filter(p -> p.id().equals("ai.mcp")).findFirst().orElseThrow();

        assertThat(mcp.optionsSources()).contains("ieum.mcp_servers");
    }

    @Test
    @DisplayName("null·모르는 이름(프리셋 id 포함)은 empty")
    void event_nullOrUnknown() {
        assertThat(CATALOG.event(null)).isEmpty();
        assertThat(CATALOG.event("nope")).isEmpty();
        assertThat(CATALOG.event("ai.mcp")).isEmpty();
        assertThat(CATALOG.triggerTypeOf(null)).isEmpty();
        assertThat(CATALOG.triggerTypeOf("nope")).isEmpty();
    }

    @Test
    @DisplayName("index.txt가 없는 클래스로더 → 서브모듈 안내와 함께 실패")
    void load_noIndex_failsWithHint(@TempDir Path dir) throws IOException {
        try (URLClassLoader empty = loader(dir)) {
            assertThatThrownBy(() -> EventCatalog.load(empty))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(EventCatalog.SUBMODULE_HINT);
        }
    }

    @Test
    @DisplayName("index.txt가 비었으면 서브모듈 안내와 함께 실패")
    void load_emptyIndex_failsWithHint(@TempDir Path dir) throws IOException {
        writeCatalog(dir, Map.of());

        try (URLClassLoader loader = loader(dir)) {
            assertThatThrownBy(() -> EventCatalog.load(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(EventCatalog.SUBMODULE_HINT);
        }
    }

    @Test
    @DisplayName("작은 카탈로그는 정상 로드된다(실패 케이스 픽스처의 기준선)")
    void load_minimalCatalog_ok(@TempDir Path dir) throws IOException {
        writeCatalog(dir, Map.of("manual/run.json", trigger("MANUAL"), "manual/send.json", action("x")));

        try (URLClassLoader loader = loader(dir)) {
            EventCatalog catalog = EventCatalog.load(loader);
            assertThat(catalog.events()).extracting(EventDefinition::name)
                    .containsExactly("manual.run", "manual.send");
            assertThat(catalog.triggerTypeOf("manual.run")).hasValue(TriggerType.MANUAL);
            assertThat(catalog.event("manual.send")).map(EventDefinition::toolKey).hasValue("x");
        }
    }

    static Stream<Arguments> invalidEvents() {
        return Stream.of(
                Arguments.of("node_type이 NodeType 값이 아님", """
                        {"node_type":"NOPE","kind":"action","tool_key":null,"fixed":{"type":"NOPE","config":{}}}""",
                        "node_type"),
                Arguments.of("fixed.type ≠ node_type", """
                        {"node_type":"TRIGGER","kind":"trigger","trigger_type":"MANUAL","tool_key":null,
                         "fixed":{"type":"ACTION","config":{}}}""", "fixed.type"),
                Arguments.of("kind가 trigger·action·search 밖", """
                        {"node_type":"TRIGGER","kind":"read","trigger_type":"MANUAL","tool_key":null,
                         "fixed":{"type":"TRIGGER","config":{}}}""", "kind"),
                Arguments.of("TRIGGER인데 trigger_type 없음", """
                        {"node_type":"TRIGGER","kind":"trigger","tool_key":null,"fixed":{"type":"TRIGGER","config":{}}}""",
                        "trigger_type"),
                Arguments.of("TRIGGER가 아닌데 trigger_type 있음", """
                        {"node_type":"ACTION","kind":"action","trigger_type":"MANUAL","tool_key":"x",
                         "fixed":{"type":"ACTION","config":{}}}""", "trigger_type"),
                Arguments.of("trigger_type이 TriggerType 값이 아님", """
                        {"node_type":"TRIGGER","kind":"trigger","trigger_type":"NOPE","tool_key":null,
                         "fixed":{"type":"TRIGGER","config":{}}}""", "NOPE"),
                Arguments.of("ACTION인데 tool_key 없음", """
                        {"node_type":"ACTION","kind":"action","tool_key":null,"fixed":{"type":"ACTION","config":{}}}""",
                        "tool_key"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvents")
    @DisplayName("이벤트 불변식 위반 → 이벤트 이름과 위반 키를 담아 실패")
    void load_invalidEvent_fails(String caseName, String json, String expected, @TempDir Path dir) throws IOException {
        writeCatalog(dir, Map.of("manual/run.json", json));

        try (URLClassLoader loader = loader(dir)) {
            assertThatThrownBy(() -> EventCatalog.load(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manual.run")
                    .hasMessageContaining(expected);
        }
    }

    @Test
    @DisplayName("앱이 apps.json에 없으면 실패")
    void load_unknownApp_fails(@TempDir Path dir) throws IOException {
        writeCatalog(dir, Map.of("nope/run.json", trigger("MANUAL")));

        try (URLClassLoader loader = loader(dir)) {
            assertThatThrownBy(() -> EventCatalog.load(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nope.run")
                    .hasMessageContaining("apps.json");
        }
    }

    @Test
    @DisplayName("trigger_type이 두 이벤트에서 겹치면 실패")
    void load_duplicateTriggerType_fails(@TempDir Path dir) throws IOException {
        writeCatalog(dir, Map.of("manual/run.json", trigger("MANUAL"), "manual/run2.json", trigger("MANUAL")));

        try (URLClassLoader loader = loader(dir)) {
            assertThatThrownBy(() -> EventCatalog.load(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("trigger_type")
                    .hasMessageContaining("MANUAL");
        }
    }

    @Test
    @DisplayName("tool_key가 두 이벤트에서 겹치면 실패")
    void load_duplicateToolKey_fails(@TempDir Path dir) throws IOException {
        writeCatalog(dir, Map.of("manual/a.json", action("dup"), "manual/b.json", action("dup")));

        try (URLClassLoader loader = loader(dir)) {
            assertThatThrownBy(() -> EventCatalog.load(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool_key")
                    .hasMessageContaining("dup");
        }
    }

    private static String trigger(String triggerType) {
        return """
                {"node_type":"TRIGGER","kind":"trigger","trigger_type":"%s","tool_key":null,
                 "fixed":{"type":"TRIGGER","config":{}}}""".formatted(triggerType);
    }

    private static String action(String toolKey) {
        return """
                {"node_type":"ACTION","kind":"action","tool_key":"%s","fixed":{"type":"ACTION","config":{}}}"""
                .formatted(toolKey);
    }

    private static URLClassLoader loader(Path dir) throws IOException {
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, null);
    }

    /** dir/catalog/ 아래에 앱 manual 하나짜리 카탈로그를 쓴다. index.txt는 경로를 정렬해서 만든다(빌드와 같은 규칙). */
    private static void writeCatalog(Path dir, Map<String, String> events) throws IOException {
        Path root = dir.resolve("catalog");
        Files.createDirectories(root);
        Map<String, String> sorted = new TreeMap<>(events);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            Path file = root.resolve("events").resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
        Files.writeString(root.resolve("index.txt"),
                sorted.isEmpty() ? "" : String.join("\n", sorted.keySet()) + "\n");
        Files.writeString(root.resolve("apps.json"), """
                {"manual":{"provider":"ieum","title":"수동","brand":"webhook","serviceType":null,"auth":null}}""");
        Files.writeString(root.resolve("presets.json"), "[]");
    }
}
