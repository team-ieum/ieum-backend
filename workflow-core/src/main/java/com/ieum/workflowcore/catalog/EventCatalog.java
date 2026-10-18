package com.ieum.workflowcore.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공유 노드 카탈로그(ieum-node-catalog 서브모듈 → 클래스패스 {@code catalog/}). 빌드가 만든 {@code catalog/index.txt}의
 * 이벤트 파일 목록만 읽는다(jar 안 폴더 목록 읽기 금지). 카탈로그 레포에 schema.json이 없으므로 BE가 기대는 불변식은
 * 여기서 직접 검사하고, 어기면 IllegalStateException으로 기동을 막는다.
 * ACTION fixed 모양과 프리셋·이벤트 id 충돌은 카탈로그 검사기(scripts/validate.py)가 막고, 칸 key 겹침은 BE와 무관하다.
 */
public final class EventCatalog {

    public static final String SUBMODULE_HINT = "`git submodule update --init`으로 catalog/를 받아라";

    private static final String ROOT = "catalog/";
    private static final Pattern PATH = Pattern.compile("([a-z][a-z0-9_]*)/([a-z][a-z0-9_]*)\\.json");
    private static final Set<String> KINDS = Set.of("trigger", "action", "search");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, EventDefinition> events;
    private final Map<String, AppDefinition> apps;
    private final List<PresetDefinition> presets;

    private EventCatalog(Map<String, EventDefinition> events, Map<String, AppDefinition> apps,
                         List<PresetDefinition> presets) {
        this.events = events;
        this.apps = apps;
        this.presets = presets;
    }

    public static EventCatalog loadFromClasspath() {
        return load(EventCatalog.class.getClassLoader());
    }

    public static EventCatalog load(ClassLoader loader) {
        List<String> paths = readIndex(loader);

        Map<String, AppDefinition> apps = new LinkedHashMap<>();
        readJson(loader, "apps.json", new TypeReference<Map<String, Map<String, Object>>>() {
        }).forEach((key, v) -> apps.put(key, new AppDefinition(key, str(v, "provider"), str(v, "title"),
                str(v, "brand"), str(v, "serviceType"), str(v, "auth"))));

        Map<String, EventDefinition> events = new TreeMap<>();
        Map<TriggerType, String> triggerOwners = new EnumMap<>(TriggerType.class);
        Map<String, String> toolKeyOwners = new HashMap<>();
        for (String path : paths) {
            EventDefinition e = parseEvent(path, readJson(loader, "events/" + path,
                    new TypeReference<Map<String, Object>>() {
                    }), apps);
            events.put(e.name(), e);
            if (e.triggerType() != null && triggerOwners.putIfAbsent(e.triggerType(), e.name()) != null) {
                throw fail(e.name(), "trigger_type " + e.triggerType() + "가 "
                        + triggerOwners.get(e.triggerType()) + "와 겹친다");
            }
            if (e.toolKey() != null && toolKeyOwners.putIfAbsent(e.toolKey(), e.name()) != null) {
                throw fail(e.name(), "tool_key '" + e.toolKey() + "'가 " + toolKeyOwners.get(e.toolKey()) + "와 겹친다");
            }
        }

        List<PresetDefinition> presets = readJson(loader, "presets.json",
                new TypeReference<List<Map<String, Object>>>() {
                }).stream().map(EventCatalog::parsePreset).toList();

        return new EventCatalog(Collections.unmodifiableMap(events), Collections.unmodifiableMap(apps), presets);
    }

    /** 카탈로그 이벤트 이름이면 그 정의. null·모르는 이름(프리셋 id 포함)은 empty. */
    public Optional<EventDefinition> event(String name) {
        return Optional.ofNullable(name == null ? null : events.get(name));
    }

    public Collection<EventDefinition> events() {
        return events.values();
    }

    /** 트리거 이벤트면 그 trigger_type. 트리거가 아니거나 모르는 이름이면 empty. */
    public Optional<TriggerType> triggerTypeOf(String event) {
        return event(event).map(EventDefinition::triggerType);
    }

    public Optional<AppDefinition> app(String key) {
        return Optional.ofNullable(key == null ? null : apps.get(key));
    }

    public Collection<AppDefinition> apps() {
        return apps.values();
    }

    public List<PresetDefinition> presets() {
        return presets;
    }

    private static EventDefinition parseEvent(String path, Map<String, Object> raw, Map<String, AppDefinition> apps) {
        Matcher m = PATH.matcher(path);
        if (!m.matches()) {
            throw fail("events/" + path, "경로는 events/<앱>/<이벤트>.json(소문자·숫자·밑줄)이어야 한다");
        }
        String app = m.group(1);
        String name = app + "." + m.group(2);
        if (!apps.containsKey(app)) {
            throw fail(name, "앱 '" + app + "'가 apps.json에 없다");
        }
        NodeType nodeType = enumOf(NodeType.class, raw.get("node_type"), name, "node_type");
        Object fixedType = raw.get("fixed") instanceof Map<?, ?> fixed ? fixed.get("type") : null;
        if (!nodeType.name().equals(fixedType)) {
            throw fail(name, "fixed.type(" + fixedType + ")이 node_type(" + nodeType + ")과 다르다");
        }
        String kind = str(raw, "kind");
        if (!KINDS.contains(kind)) {
            throw fail(name, "kind '" + kind + "'가 " + KINDS + "에 없다");
        }
        Object declaredTrigger = raw.get("trigger_type");
        if ((nodeType == NodeType.TRIGGER) != (declaredTrigger != null)) {
            throw fail(name, "trigger_type은 TRIGGER 이벤트에만 있고, TRIGGER 이벤트엔 반드시 있어야 한다");
        }
        TriggerType triggerType = declaredTrigger == null ? null
                : enumOf(TriggerType.class, declaredTrigger, name, "trigger_type");
        String toolKey = str(raw, "tool_key");
        if (nodeType == NodeType.ACTION && (toolKey == null || toolKey.isBlank())) {
            throw fail(name, "ACTION 이벤트에 tool_key가 없다");
        }
        return new EventDefinition(name, app, nodeType, kind, triggerType, toolKey,
                !Boolean.FALSE.equals(raw.get("builder")), !Boolean.FALSE.equals(raw.get("generation")),
                optionsSources(raw));
    }

    private static PresetDefinition parsePreset(Map<String, Object> raw) {
        String id = str(raw, "id");
        if (id == null || id.isBlank()) {
            throw fail("presets.json", "id가 없는 프리셋이 있다");
        }
        return new PresetDefinition(id, optionsSources(raw));
    }

    /** fields(칸 → 부분 Field)와 inputFields(Field 목록)를 children까지 훑어 optionsSource 값을 모은다. */
    private static Set<String> optionsSources(Map<String, Object> raw) {
        Set<String> out = new TreeSet<>();
        collectOptionsSources(raw.get("fields"), out);
        collectOptionsSources(raw.get("inputFields"), out);
        return Collections.unmodifiableSet(out);
    }

    private static void collectOptionsSources(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("optionsSource") instanceof String source) {
                out.add(source);
            }
            map.values().forEach(v -> collectOptionsSources(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectOptionsSources(v, out));
        }
    }

    private static List<String> readIndex(ClassLoader loader) {
        List<String> paths;
        try (InputStream in = open(loader, "index.txt")) {
            paths = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("카탈로그 " + ROOT + "index.txt를 읽지 못했다 — " + SUBMODULE_HINT, e);
        }
        if (paths.isEmpty()) {
            throw new IllegalStateException("카탈로그 이벤트가 없다(" + ROOT + "index.txt 비어 있음) — " + SUBMODULE_HINT);
        }
        return paths;
    }

    private static <T> T readJson(ClassLoader loader, String file, TypeReference<T> type) {
        try (InputStream in = open(loader, file)) {
            T value = MAPPER.readValue(in, type);
            if (value == null) {
                throw fail(ROOT + file, "내용이 null이다");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("카탈로그 " + ROOT + file + ": JSON 파싱 실패 - " + e.getMessage(), e);
        }
    }

    private static InputStream open(ClassLoader loader, String file) {
        InputStream in = loader.getResourceAsStream(ROOT + file);
        if (in == null) {
            throw new IllegalStateException("카탈로그 " + ROOT + file + "가 없다 — " + SUBMODULE_HINT);
        }
        return in;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object value, String where, String key) {
        if (value instanceof String s) {
            for (E e : type.getEnumConstants()) {
                if (e.name().equals(s)) {
                    return e;
                }
            }
        }
        throw fail(where, key + " '" + value + "'가 " + type.getSimpleName() + " 값이 아니다");
    }

    private static String str(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }

    private static IllegalStateException fail(String where, String message) {
        return new IllegalStateException("카탈로그 " + where + ": " + message);
    }
}
