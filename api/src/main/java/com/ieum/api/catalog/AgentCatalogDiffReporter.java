package com.ieum.api.catalog;

import com.ieum.api.chat.service.AgentClient;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 뒤 agent 노드 카탈로그({@code GET /v1/nodes/catalog})의 항목 id와 BE 카탈로그 builder 이벤트 이름을 대조해
 * 차이를 경고로만 남긴다 (IEUM-BE-93). 두 서버가 다른 카탈로그 서브모듈 커밋으로 배포됐는지 알아채는 용도다.
 *
 * <p>기동을 막지 않는다 — agent가 늦게 뜨거나 내려가 있어도 BE는 떠야 하므로 예외는 전부 삼킨다. 비교 기준이 builder인
 * 이유: agent 응답은 builder 항목만 싣는다(builder=false인 gmail.send_email은 늘 빠진다). 응답에 버전·해시가 없어 id 집합으로만 본다.
 * 테스트 컨텍스트는 agent가 없으므로 {@code ieum.catalog.agent-diff-check: false}로 끈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.catalog.agent-diff-check", havingValue = "true", matchIfMissing = true)
public class AgentCatalogDiffReporter {

    private final AgentClient agentClient;
    private final EventCatalog eventCatalog;

    record Diff(Set<String> onlyBe, Set<String> onlyAgent) {
    }

    /** 기동을 붙잡지 않게 가상 스레드로 넘긴다 — 공유 풀(workflowExecutor)에 제출하면 포화 시 거부 예외가 기동을 실패시킨다. */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        Thread.ofVirtual().name("catalog-diff").start(this::compare);
    }

    void compare() {
        try {
            Set<String> be = eventCatalog.events().stream()
                    .filter(EventDefinition::builder).map(EventDefinition::name)
                    .collect(Collectors.toSet());
            Set<String> agent = new TreeSet<>();
            agentClient.getNodeCatalog().path("entries").forEach(entry -> agent.add(entry.path("id").asText()));

            Diff diff = diff(be, agent);
            diff.onlyBe().forEach(id -> log.warn("[CatalogDiff] BE에만 있는 노드: {}", id));
            diff.onlyAgent().forEach(id -> log.warn("[CatalogDiff] agent에만 있는 노드: {}", id));
        } catch (Exception e) {
            log.warn("[CatalogDiff] agent 카탈로그 대조 실패 — 건너뜀: {}", e.getMessage());
        }
    }

    static Diff diff(Set<String> be, Set<String> agent) {
        Set<String> onlyBe = new TreeSet<>(be);
        onlyBe.removeAll(agent);
        Set<String> onlyAgent = new TreeSet<>(agent);
        onlyAgent.removeAll(be);
        return new Diff(onlyBe, onlyAgent);
    }
}
