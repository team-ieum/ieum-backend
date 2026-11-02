package com.ieum.workflowcore.scheduler;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.NodeDerivation;
import java.time.ZoneId;
import java.util.Map;

/**
 * 스케줄 트리거 노드의 칸(hour·weekday·day·weekends)으로 Quartz 6필드 cron을 만든다.
 *
 * <p>요일은 이름(MON…SUN) 그대로 넣는다 — 숫자는 Unix·Quartz 기준이 달라 하루 어긋난다({@link CronConverter} 참고).
 * {@code schedule.every} 등 그 밖의 event는 {@code config.cron} 원문을 돌려주고 변환·검증은 기존 경로에 맡긴다.
 */
public final class ScheduleCron {

    /** 스케줄 시각의 기준 타임존 — 서버(JVM) 기본값과 무관하게 KST. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final String HOUR = "1?\\d|2[0-3]";
    private static final String WEEKDAY = "MON|TUE|WED|THU|FRI|SAT|SUN";
    private static final String DAY = "[1-9]|1\\d|2[0-8]|L";

    private ScheduleCron() {}

    public static String of(Object nodeId, String event, Map<?, ?> config) {
        return switch (event == null ? "" : event) {
            case "schedule.hourly" -> "0 0 * " + days(config);
            case "schedule.daily" -> "0 0 " + field(nodeId, "매일", config, "hour", HOUR, "시각을") + " " + days(config);
            case "schedule.weekly" -> {
                String weekday = field(nodeId, "매주", config, "weekday", WEEKDAY, "요일을");
                yield "0 0 " + field(nodeId, "매주", config, "hour", HOUR, "시각을") + " ? * " + weekday;
            }
            case "schedule.monthly" -> {
                String day = field(nodeId, "매월", config, "day", DAY, "날짜를");
                yield "0 0 " + field(nodeId, "매월", config, "hour", HOUR, "시각을") + " " + day + " * ?";
            }
            default -> config.get("cron") instanceof String cron ? cron : null;
        };
    }

    /** 일·월·요일 필드. weekends는 명시적 false일 때만 평일 한정(FE는 기본값 true를 저장하지 않는다). */
    private static String days(Map<?, ?> config) {
        Object weekends = config.get("weekends");
        return Boolean.FALSE.equals(weekends) || "false".equals(weekends) ? "? * MON-FRI" : "* * ?";
    }

    private static String field(Object nodeId, String title, Map<?, ?> config, String key, String allowed,
                                String phrase) {
        Object value = config.get(key);
        String text = value == null ? "" : String.valueOf(value);
        if (!text.matches(allowed)) {
            throw new CustomException(ErrorCode.INVALID_CRON_EXPRESSION,
                title + " 노드(" + NodeDerivation.echo(nodeId) + ")의 " + phrase + " 골라 주세요");
        }
        return text;
    }
}
