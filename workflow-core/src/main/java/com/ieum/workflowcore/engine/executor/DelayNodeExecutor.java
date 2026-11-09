package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.service.DelayQueueService;
import com.ieum.workflowcore.service.DelayQueueService.Slot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DELAY 노드(기다리기) — 재개 시각을 계산한다. 실행 중 기다림은 런타임이 보류(WAITING_DELAY)로 처리하고,
 * 이 실행기의 {@link #execute}는 노드 단일 테스트 경로다: 기다리지 않고 예정 시각을 출력한다(큐는 예약하지 않고 peek).
 *
 * <p>이벤트별 칸: {@code delay.for} = amount·unit, {@code delay.until} = until·ifPast,
 * {@code delay.queue} = queueName·amount·unit. 기본값(ifPast = continue_1d)은 여기서 적용한다.
 */
@Component
public class DelayNodeExecutor implements NodeExecutor {

    public static final Set<String> EVENTS = Set.of("delay.for", "delay.until", "delay.queue");
    public static final Duration MIN_WAIT = Duration.ofMinutes(1);
    public static final Duration MAX_WAIT = Duration.ofDays(30);

    private static final int MAX_QUEUE_NAME = 100;
    private static final String RANGE = "기다리는 시간은 1분 이상 30일 이하여야 해요";

    private final DelayQueueService queues;
    private final Clock clock;

    @Autowired
    public DelayNodeExecutor(DelayQueueService queues) {
        this(queues, Clock.system(ConditionValues.KST));
    }

    public DelayNodeExecutor(DelayQueueService queues, Clock clock) {
        this.queues = queues;
        this.clock = clock;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.DELAY;
    }

    /** 재개 시각을 계산한다. {@code reserveQueue}가 false면 큐 슬롯을 쓰지 않는다(노드 테스트). 실패는 {@link DelayFailure}. */
    public DelayPlan plan(String event, Map<String, Object> input, UUID userId, boolean reserveQueue) {
        Instant now = clock.instant();
        if (event == null || !EVENTS.contains(event)) {
            throw new DelayFailure("지원하지 않는 기다리기예요: " + event);
        }
        return switch (event) {
            case "delay.for" -> new DelayPlan(now.plus(span(input)), now, null);
            case "delay.until" -> new DelayPlan(until(input, now), now, null);
            default -> queue(input, userId, reserveQueue, now);
        };
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long start = System.currentTimeMillis();
        try {
            DelayPlan plan = plan(node.getEvent(), input, cursor.getContext().getUserId(), false);
            Instant waitedUntil = plan.immediate() ? plan.decidedAt() : plan.scheduledFor();
            return ExecutorResult.success(plan.releasedOutput(waitedUntil), System.currentTimeMillis() - start);
        } catch (DelayFailure f) {
            return ExecutorResult.failure(f.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private DelayPlan queue(Map<String, Object> input, UUID userId, boolean reserve, Instant now) {
        Object raw = input.get("queueName");
        String name = raw == null ? "" : String.valueOf(raw).strip();
        if (name.isEmpty()) throw new DelayFailure("'큐 이름' 칸이 비어 있어요");
        if (name.length() > MAX_QUEUE_NAME) throw new DelayFailure("'큐 이름'은 100자 이하여야 해요");
        Duration interval = span(input);
        Slot slot = reserve ? queues.reserve(userId, name, interval, now) : queues.peek(userId, name, interval, now);
        return new DelayPlan(slot.releaseAt(), now, slot.position());
    }

    /** amount×unit. 상한은 Duration 곱셈 전에 분 단위 BigDecimal로 비교한다(거대 정수도 같은 문구). */
    private static Duration span(Map<String, Object> input) {
        BigDecimal amount = ConditionValues.number(input.get("amount"))
            .filter(n -> n.stripTrailingZeros().scale() <= 0 && n.compareTo(BigDecimal.ONE) >= 0)
            .orElseThrow(() -> new DelayFailure("'기다릴 양'은 1 이상의 정수여야 해요"));
        Duration unit = unit(input.get("unit"));
        BigDecimal minutes = amount.multiply(BigDecimal.valueOf(unit.toMinutes()));
        if (minutes.compareTo(BigDecimal.valueOf(MIN_WAIT.toMinutes())) < 0
                || minutes.compareTo(BigDecimal.valueOf(MAX_WAIT.toMinutes())) > 0) {
            throw new DelayFailure(RANGE);
        }
        return Duration.ofMinutes(minutes.longValueExact());
    }

    private static Duration unit(Object value) {
        if (value == null) throw new DelayFailure("'단위'를 골라 주세요");
        return switch (String.valueOf(value)) {
            case "minutes" -> Duration.ofMinutes(1);
            case "hours" -> Duration.ofHours(1);
            case "days" -> Duration.ofDays(1);
            case "weeks" -> Duration.ofDays(7);
            default -> throw new DelayFailure("'단위'를 골라 주세요");
        };
    }

    private Instant until(Map<String, Object> input, Instant now) {
        Duration pastLimit = pastLimit(input.get("ifPast"));
        Object raw = input.get("until");
        Instant target = ConditionValues.date(raw, ZonedDateTime.now(clock))
            .map(ConditionValues.When::instant)
            .orElseThrow(() -> new DelayFailure("시각으로 읽지 못했어요: '" + raw + "'"));
        if (target.isAfter(now)) {
            // 미래면 30일 상한만 본다 — 1분 미만이면 다음 sweep에 재개한다.
            if (target.isAfter(now.plus(MAX_WAIT))) throw new DelayFailure(RANGE);
            return target;
        }
        if (pastLimit != null && Duration.between(target, now).compareTo(pastLimit) > 0) {
            throw new DelayFailure("정한 시각(" + DelayPlan.format(target) + ")이 이미 지났어요");
        }
        return target;
    }

    /** 지난 시각을 허용하는 한도. null = 늘 통과(always). */
    private static Duration pastLimit(Object value) {
        if (value == null) return Duration.ofDays(1);
        return switch (String.valueOf(value)) {
            case "continue_15m" -> Duration.ofMinutes(15);
            case "continue_1h" -> Duration.ofHours(1);
            case "continue_1d" -> Duration.ofDays(1);
            case "always" -> null;
            default -> throw new DelayFailure("'이미 지난 시각이면' 값이 올바르지 않아요");
        };
    }
}
