package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.service.DelayQueueService;
import com.ieum.workflowcore.service.DelayQueueService.Slot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelayNodeExecutorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T01:30:00Z"); // KST 10:30
    private static final String RANGE = "기다리는 시간은 1분 이상 30일 이하여야 해요";
    private static final String AMOUNT = "'기다릴 양'은 1 이상의 정수여야 해요";
    private static final String UNIT = "'단위'를 골라 주세요";

    private final DelayQueueService queues = mock(DelayQueueService.class);
    private final DelayNodeExecutor executor =
        new DelayNodeExecutor(queues, Clock.fixed(NOW, ConditionValues.KST));
    private final UUID u = UUID.randomUUID();
    private final UUID exec = UUID.randomUUID();

    private DelayPlan plan(String event, Map<String, Object> input) {
        return executor.plan(event, input, u, exec);
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void assertFails(String event, Map<String, Object> input, String message) {
        assertThatThrownBy(() -> plan(event, input)).isInstanceOf(DelayFailure.class).hasMessage(message);
    }

    @Test
    void for_10분() {
        DelayPlan p = plan("delay.for", config("amount", 10, "unit", "minutes"));

        assertThat(p.scheduledFor()).isEqualTo(Instant.parse("2026-11-09T01:40:00Z"));
        assertThat(p.immediate()).isFalse();
        assertThat(p.queuePosition()).isNull();
        assertThat(p.waitingOutput()).isEqualTo(Map.of("scheduledFor", "2026-11-09T10:40:00+09:00"));
    }

    @Test
    void for_문자열_양() {
        DelayPlan p = plan("delay.for", config("amount", "2", "unit", "weeks"));

        assertThat(DelayPlan.format(p.scheduledFor())).isEqualTo("2026-11-23T10:30:00+09:00");
    }

    @Test
    void for_30일_정확히는_허용() {
        DelayPlan p = plan("delay.for", config("amount", 30, "unit", "days"));

        assertThat(p.scheduledFor()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void for_한도_밖_실패() {
        assertFails("delay.for", config("amount", 31, "unit", "days"), RANGE);
        assertFails("delay.for", config("amount", 5, "unit", "weeks"), RANGE);
        assertFails("delay.for", config("amount", "99999999999999999999", "unit", "minutes"), RANGE);

        assertFails("delay.for", config("amount", 0, "unit", "minutes"), AMOUNT);
        assertFails("delay.for", config("amount", "1.5", "unit", "hours"), AMOUNT);
        assertFails("delay.for", config("amount", "abc", "unit", "hours"), AMOUNT);

        assertFails("delay.for", config("amount", 1), UNIT);
        assertFails("delay.for", config("amount", 1, "unit", "months"), UNIT);
    }

    @Test
    void until_시간대_없으면_KST() {
        DelayPlan p = plan("delay.until", config("until", "2026-11-09T15:00"));

        assertThat(DelayPlan.format(p.scheduledFor())).isEqualTo("2026-11-09T15:00:00+09:00");
    }

    @Test
    void until_날짜만은_KST_자정() {
        DelayPlan p = plan("delay.until", config("until", "2026-11-10"));

        assertThat(DelayPlan.format(p.scheduledFor())).isEqualTo("2026-11-10T00:00:00+09:00");
    }

    @Test
    void until_오프셋() {
        DelayPlan p = plan("delay.until", config("until", "2026-11-09T09:00:00Z"));

        assertThat(DelayPlan.format(p.scheduledFor())).isEqualTo("2026-11-09T18:00:00+09:00");
    }

    @Test
    void until_지난_시각_기본_1일() {
        assertThat(plan("delay.until", config("until", "2026-11-08T12:00")).immediate()).isTrue();

        assertFails("delay.until", config("until", "2026-11-08T10:00"),
            "정한 시각(2026-11-08T10:00:00+09:00)이 이미 지났어요");
    }

    @Test
    void until_지난_시각_15분() {
        assertThat(plan("delay.until", config("until", "2026-11-09T10:20", "ifPast", "continue_15m")).immediate())
            .isTrue();

        assertFails("delay.until", config("until", "2026-11-09T10:10", "ifPast", "continue_15m"),
            "정한 시각(2026-11-09T10:10:00+09:00)이 이미 지났어요");
    }

    @Test
    void until_always() {
        assertThat(plan("delay.until", config("until", "2025-11-09T10:30", "ifPast", "always")).immediate()).isTrue();
    }

    @Test
    void until_30일_초과_실패() {
        assertFails("delay.until", config("until", "2026-12-09T10:31"), RANGE);
    }

    @Test
    void until_읽지_못함() {
        assertFails("delay.until", config("until", "다음주"), "시각으로 읽지 못했어요: '다음주'");
    }

    @Test
    void until_읽지_못한_원문은_50자까지() {
        String longText = "가".repeat(60);

        assertFails("delay.until", config("until", longText), "시각으로 읽지 못했어요: '" + "가".repeat(50) + "…'");
    }

    @Test
    void until_ifPast_목록밖() {
        assertFails("delay.until", config("until", "2026-11-09T15:00", "ifPast", "soon"),
            "'이미 지난 시각이면' 값이 올바르지 않아요");
    }

    @Test
    void queue_예약() {
        given(queues.reserve(u, "slack", Duration.ofMinutes(1), NOW, exec))
            .willReturn(new Slot(NOW.plus(Duration.ofMinutes(10)), 3));

        DelayPlan p = executor.plan("delay.queue", config("queueName", " slack ", "amount", 1, "unit", "minutes"),
            u, exec);

        assertThat(p.scheduledFor()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(p.queuePosition()).isEqualTo(3);
        verify(queues, never()).peek(any(), any(), any(), any());
    }

    @Test
    void queue_테스트는_peek() {
        given(queues.peek(u, "slack", Duration.ofMinutes(1), NOW)).willReturn(new Slot(NOW, 0));

        executor.plan("delay.queue", config("queueName", "slack", "amount", 1, "unit", "minutes"), u, null);

        verify(queues).peek(u, "slack", Duration.ofMinutes(1), NOW);
        verify(queues, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void queue_이름_검사() {
        assertFails("delay.queue", config("queueName", "  ", "amount", 1, "unit", "minutes"),
            "'큐 이름' 칸이 비어 있어요");
        assertFails("delay.queue", config("queueName", "a".repeat(101), "amount", 1, "unit", "minutes"),
            "'큐 이름'은 100자 이하여야 해요");
    }

    @Test
    void queue_한도_실패는_그대로() {
        given(queues.reserve(any(), any(), any(), any(), any()))
            .willThrow(new DelayFailure("큐 'slack'에 기다리는 실행이 100개를 넘었어요"));

        assertFails("delay.queue", config("queueName", "slack", "amount", 1, "unit", "minutes"),
            "큐 'slack'에 기다리는 실행이 100개를 넘었어요");
    }

    @Test
    void 모르는_event() {
        assertFails("delay.nope", config(), "지원하지 않는 기다리기예요: delay.nope");
        assertFails(null, config(), "지원하지 않는 기다리기예요: null");
    }

    @Test
    void execute_노드테스트_출력() throws Exception {
        ExecutionCursor cursor = cursor();

        ExecutorResult waited = executor.execute(
            node("delay.for", config("amount", 10, "unit", "minutes")), config("amount", 10, "unit", "minutes"),
            cursor);
        assertThat(waited.isSuccess()).isTrue();
        assertThat(waited.getOutput()).isEqualTo(Map.of(
            "scheduledFor", "2026-11-09T10:40:00+09:00", "waitedUntil", "2026-11-09T10:40:00+09:00"));

        ExecutorResult passed = executor.execute(
            node("delay.until", config("until", "2026-11-09T10:20")), config("until", "2026-11-09T10:20"), cursor);
        assertThat(passed.getOutput()).containsEntry("waitedUntil", "2026-11-09T10:30:00+09:00");

        ExecutorResult failed = executor.execute(
            node("delay.for", config("amount", 0, "unit", "minutes")), config("amount", 0, "unit", "minutes"), cursor);
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getErrorMessage()).isEqualTo(AMOUNT);
    }

    @Test
    void format_초_절사() {
        assertThat(DelayPlan.format(Instant.parse("2026-11-09T01:30:00.789Z"))).isEqualTo("2026-11-09T10:30:00+09:00");
    }

    private Node node(String event, Map<String, Object> config) {
        return new Node("d", NodeType.DELAY, "기다리기", config, event);
    }

    private ExecutionCursor cursor() {
        ExecutionContext context = new ExecutionContext();
        context.setUserId(u);
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setContext(context);
        cursor.setAllNodes(List.of());
        cursor.setAllEdges(List.of());
        return cursor;
    }
}
