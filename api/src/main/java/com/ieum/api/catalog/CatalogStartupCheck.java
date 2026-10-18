package com.ieum.api.catalog;

import com.ieum.api.integration.options.OptionSource;
import com.ieum.workflowcore.catalog.AppDefinition;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.catalog.PresetDefinition;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.ToolAuthRule;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 기동 검사 (IEUM-BE-93). 카탈로그·트리거 핸들러·옵션 공급원·도구 인증 규칙이 어긋나면 생성자에서
 * IllegalStateException을 던져 컨텍스트 로드를 막는다(CatalogConfig·IntegrationOptionService와 같은 방식 —
 * ApplicationRunner면 다른 Runner가 먼저 돈 뒤에 실패한다).
 *
 * <p>"카탈로그 trigger_type ⊆ TriggerType"은 {@link EventCatalog} 로더가 이미 막으므로 여기서는 반대 방향
 * (TriggerType 값마다 핸들러)만 본다.
 */
@Component
public class CatalogStartupCheck {

    public CatalogStartupCheck(EventCatalog eventCatalog, TriggerHandlerRegistry triggerHandlers,
                               List<OptionSource> optionSources) {
        Set<String> keys = optionSources.stream().map(OptionSource::key).collect(Collectors.toSet());
        List<String> problems = problems(eventCatalog, triggerHandlers.types(), keys);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("카탈로그 기동 검사 실패:\n- " + String.join("\n- ", problems));
        }
    }

    /**
     * @param handled    핸들러가 등록된 트리거 종류
     * @param optionKeys OptionSource 빈 키 전부
     * @return 문제 문장 목록(없으면 빈 목록)
     */
    static List<String> problems(EventCatalog catalog, Set<TriggerType> handled, Set<String> optionKeys) {
        List<String> problems = new ArrayList<>();
        for (TriggerType type : TriggerType.values()) {
            if (!handled.contains(type)) {
                problems.add("트리거 핸들러 없음: " + type);
            }
        }
        for (EventDefinition e : catalog.events()) {
            missingSources(e.optionsSources(), optionKeys, e.name(), problems);
        }
        for (PresetDefinition p : catalog.presets()) {
            missingSources(p.optionsSources(), optionKeys, p.id(), problems);
        }
        for (AppDefinition app : catalog.apps()) {
            if (app.auth() != null && !ToolAuthRule.KNOWN_AUTHS.contains(app.auth())) {
                problems.add("알 수 없는 auth: " + app.auth() + " (앱 " + app.key() + ")");
            }
        }
        // 실행 가능한 ACTION만(= builder. ACTION은 전부 generation=false) — builder=false인 gmail.send_email(tool_key gmail)은 빠진다
        for (EventDefinition e : catalog.events()) {
            if (e.nodeType() != NodeType.ACTION || !e.builder()) {
                continue;
            }
            String expected = ToolAuthRule.authOf(e.toolKey());
            String appAuth = catalog.app(e.app()).map(AppDefinition::auth).orElse(null);
            if (!Objects.equals(expected, appAuth)) {
                problems.add("tool_key 인증 불일치: " + e.name() + " tool_key=" + e.toolKey()
                        + " 기대 auth=" + expected + " 앱 auth=" + appAuth);
            }
        }
        return problems;
    }

    private static void missingSources(Set<String> sources, Set<String> keys, String owner, List<String> problems) {
        for (String source : sources) {
            if (!keys.contains(source)) {
                problems.add("optionsSource 공급원 없음: " + source + " (" + owner + ")");
            }
        }
    }
}
