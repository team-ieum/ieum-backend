package com.ieum.workflowcore.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BuiltinTriggerHandlersTest {

    private static Node node(Map<String, Object> config) {
        return new Node("t1", NodeType.TRIGGER, "트리거", config);
    }

    @Test
    @DisplayName("MANUAL — 치환된 config(input)를 출력하고 심어 둔 페이로드는 무시한다, 노드 테스트는 RUN")
    void manualEchoesInputAndIgnoresPlanted() {
        ManualTriggerHandler handler = new ManualTriggerHandler();
        Map<String, Object> input = Map.of("x", "rendered");

        Map<String, Object> output = handler.output(
            node(new HashMap<>(Map.of("x", "{{nodes.t1.output.a}}"))), input, Map.of("planted", true));

        assertThat(handler.type()).isEqualTo(TriggerType.MANUAL);
        assertThat(output).isEqualTo(input);
        output.put("extra", 1);   // 출력은 input 사본이다 — 수정해도 input이 바뀌지 않는다(Map.of라면 예외)
        assertThat(handler.nodeTestMode()).isEqualTo(NodeTestMode.RUN);
    }

    @Test
    @DisplayName("SCHEDULE — {triggeredAt(ISO_LOCAL_DATE_TIME), cron}을 내고 input·planted는 섞지 않는다")
    void scheduleEmitsTriggeredAtAndCron() {
        ScheduleTriggerHandler handler = new ScheduleTriggerHandler();

        Map<String, Object> output = handler.output(
            node(new HashMap<>(Map.of("cron", "0 0 9 * * ?"))), Map.of("x", 1), Map.of("planted", true));

        assertThat(handler.type()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(output).containsOnlyKeys("triggeredAt", "cron").containsEntry("cron", "0 0 9 * * ?");
        assertThat(LocalDateTime.parse((String) output.get("triggeredAt"))).isNotNull();
        assertThat(handler.nodeTestMode()).isEqualTo(NodeTestMode.RUN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("SCHEDULE — cron이 없거나 비면 IllegalStateException(실행기가 failure로 바꾼다)")
    void scheduleWithoutCronThrows(String cron) {
        Map<String, Object> config = new HashMap<>();
        if (cron != null) {
            config.put("cron", cron);
        }

        assertThatThrownBy(() -> new ScheduleTriggerHandler().output(node(config), Map.of(), Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("SCHEDULE 트리거에 cron 필드가 없습니다. nodeId: t1");
    }

    @Test
    @DisplayName("WEBHOOK — 심어 둔 페이로드를 복사해 출력하고(config·input은 섞지 않는다), 노드 테스트는 LISTEN")
    void webhookCopiesPlantedAndListens() {
        WebhookTriggerHandler handler = new WebhookTriggerHandler();
        Map<String, Object> planted = Map.of("orderId", "ORD-1");

        Map<String, Object> output = handler.output(
            node(new HashMap<>(Map.of("includeRaw", true))), Map.of("includeRaw", true), planted);

        assertThat(handler.type()).isEqualTo(TriggerType.WEBHOOK);
        assertThat(output).isEqualTo(planted);
        output.put("extra", 1);   // 사본이다(planted는 불변 Map이라 원본이면 예외)
        assertThat(handler.nodeTestMode()).isEqualTo(NodeTestMode.LISTEN);
    }

    @Test
    @DisplayName("기본값 — 구독 안 함·빈 스냅샷·resourceKey null·PollStart.NONE·검증 없음·샘플 없음")
    void defaultsAreInert() {
        TriggerHandler handler = () -> TriggerType.GMAIL_NEW_EMAIL;

        assertThat(handler.subscribes()).isFalse();
        assertThat(handler.snapshot(Map.of("query", "x"))).isEmpty();
        assertThat(handler.resourceKey(Map.of())).isNull();
        assertThat(handler.pollStart(LocalDateTime.now())).isSameAs(PollStart.NONE);
        assertThat(PollStart.NONE.cursor()).isNull();
        assertThat(PollStart.NONE.nextPollAt()).isNull();
        handler.verifyAccess(UUID.randomUUID(), Map.of());
        assertThat(handler.fetchSample(UUID.randomUUID(), Map.of())).isEmpty();
    }
}
