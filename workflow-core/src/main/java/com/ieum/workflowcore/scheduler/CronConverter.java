package com.ieum.workflowcore.scheduler;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.regex.Pattern;
import org.quartz.CronExpression;

/**
 * agent가 만드는 Unix 5필드 cron(분 시 일 월 요일)을 Quartz 형식(초 분 시 일 월 요일)으로 바꾼다 (IEUM-AI-56).
 *
 * <p>이미 6/7필드면 Quartz 형식으로 보고 그대로 둔다 — REST 경로가 받는 형식이 Quartz라 같은 값을
 * 두 번 거쳐도 결과가 같다. 결과는 항상 {@link CronExpression#isValidExpression}으로 검증한다.
 */
public final class CronConverter {

    /** step('/' 뒤) 숫자를 제외한 요일 숫자. */
    private static final Pattern DAY_NUMBER = Pattern.compile("(?<![/\\d])\\d+");

    private CronConverter() {}

    public static String toQuartz(String cron) {
        if (cron == null || cron.isBlank()) {
            throw invalid("cron 표현식이 비어 있습니다.");
        }
        String[] fields = cron.trim().split("\\s+");
        String quartz = switch (fields.length) {
            case 5 -> fromUnix(fields);
            case 6, 7 -> String.join(" ", fields);
            default -> throw invalid("cron 필드 수는 5(Unix) 또는 6/7(Quartz)이어야 합니다: " + cron);
        };
        if (!CronExpression.isValidExpression(quartz)) {
            throw invalid("올바르지 않은 cron 표현식입니다: " + cron);
        }
        return quartz;
    }

    /** Quartz는 일·요일 중 하나가 반드시 '?'여야 해서 둘 다 지정된 Unix 식(OR 의미)은 옮길 수 없다. */
    private static String fromUnix(String[] f) {
        String dayOfMonth = "?".equals(f[2]) ? "*" : f[2];
        String dayOfWeek = "?".equals(f[4]) ? "*" : f[4];
        if ("*".equals(dayOfWeek)) {
            dayOfWeek = "?";
        } else if ("*".equals(dayOfMonth)) {
            dayOfMonth = "?";
            dayOfWeek = toQuartzDays(dayOfWeek);
        } else {
            throw invalid("일과 요일을 동시에 지정한 cron은 지원하지 않습니다: " + String.join(" ", f));
        }
        return String.join(" ", "0", f[0], f[1], dayOfMonth, f[3], dayOfWeek);
    }

    /**
     * Unix 요일 숫자(0·7=일)를 Quartz 숫자(1=일)로 바꾼다. 그대로 넘기면 Unix 5(금)가 Quartz 5(목)로
     * 하루 어긋나고, 이름(MON)은 Quartz가 범위 뒤 step을 검증 통과한 채 버리므로 쓰지 않는다.
     */
    private static String toQuartzDays(String dayOfWeek) {
        if (dayOfWeek.matches(".*[A-Za-z].*") && dayOfWeek.contains("/")) {
            throw invalid("요일 이름에는 step('/')을 붙일 수 없습니다: " + dayOfWeek);
        }
        // Unix 0-7(매일)은 숫자로 옮기면 1-1(일요일만)이 된다.
        String days = dayOfWeek.replaceFirst("^0-7(?=/|$)", "*");
        return DAY_NUMBER.matcher(days).replaceAll(m -> {
            if (!m.group().matches("[0-7]")) {
                throw invalid("요일은 0~7이어야 합니다: " + dayOfWeek);
            }
            return String.valueOf(Integer.parseInt(m.group()) % 7 + 1);
        });
    }

    private static CustomException invalid(String detail) {
        return new CustomException(ErrorCode.INVALID_CRON_EXPRESSION, detail);
    }
}
