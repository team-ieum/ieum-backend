package com.ieum.workflowcore.scheduler;

import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.CronExpression;

class CronConverterTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @ParameterizedTest(name = "[{0}] → [{1}]")
    @CsvSource(delimiter = '|', value = {
        "0 9 * * *         | 0 0 9 * * ?",
        "0 17 * * 5        | 0 0 17 ? * 6",
        "0 9 * * 1-5       | 0 0 9 ? * 2-6",
        "0 9 1 * *         | 0 0 9 1 * ?",
        "*/15 * * * *      | 0 */15 * * * ?",
        "0 9 * * 7         | 0 0 9 ? * 1",
        "0 9 * * 0         | 0 0 9 ? * 1",
        "0 9 * * 1,3       | 0 0 9 ? * 2,4",
        "0 9 * * 1-5/2     | 0 0 9 ? * 2-6/2",
        "0 9 * * 5-7       | 0 0 9 ? * 6-1",
        "0 9 * * 0-7       | 0 0 9 ? * *",
        "0 9 * * MON       | 0 0 9 ? * MON",
        "0 9 ? * 1         | 0 0 9 ? * 2",
        "0 0 10 * * ?      | 0 0 10 * * ?",
        "0 0 10 * * ? 2030 | 0 0 10 * * ? 2030",
    })
    @DisplayName("Unix 5필드는 Quartz로 변환하고, 이미 Quartz(6/7필드)면 그대로 통과시킨다")
    void toQuartz_converts(String unix, String quartz) {
        assertThat(CronConverter.toQuartz(unix)).isEqualTo(quartz);
    }

    /**
     * 문자열 일치로는 못 잡는다 — Quartz는 요일 번호 체계가 Unix와 하루 어긋나고, 이름 범위에 붙은
     * step은 검증을 통과한 채 조용히 버린다. 실제 다음 실행일들의 요일로 본다.
     */
    @ParameterizedTest(name = "[{0}] → {1}")
    @CsvSource(delimiter = '|', value = {
        "0 17 * * 5     | FRIDAY",
        "0 9 * * 1-5    | MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY",
        "0 9 * * 1-5/2  | MONDAY,WEDNESDAY,FRIDAY",
        "0 9 * * 0-6/2  | SUNDAY,TUESDAY,THURSDAY,SATURDAY",
        "0 9 * * */2    | SUNDAY,TUESDAY,THURSDAY,SATURDAY",
        "0 9 * * 5-7    | FRIDAY,SATURDAY,SUNDAY",
        "0 9 * * 0-7    | MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
    })
    @DisplayName("변환된 식은 Unix가 뜻한 요일에만 실행된다")
    void toQuartz_firesOnUnixDays(String unix, String expectedDays) throws Exception {
        CronExpression expression = new CronExpression(CronConverter.toQuartz(unix));
        Set<DayOfWeek> fired = EnumSet.noneOf(DayOfWeek.class);
        Date cursor = Date.from(LocalDate.of(2026, 9, 28).atStartOfDay(ZONE).toInstant());
        for (int i = 0; i < 14; i++) {
            cursor = expression.getNextValidTimeAfter(cursor);
            fired.add(cursor.toInstant().atZone(ZONE).getDayOfWeek());
        }

        Set<DayOfWeek> expected = Arrays.stream(expectedDays.split(","))
            .map(DayOfWeek::valueOf)
            .collect(toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        assertThat(fired).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "0 9 1 * 1",          // 일·요일 동시 지정 — Quartz는 표현 불가
        "0 9 * *",            // 필드 수 부족
        "0 0 9 * * ? 2030 1", // 필드 수 초과
        "0 9 * * 8",          // 요일 범위 밖
        "0 9 * * MON-FRI/2",  // 이름 + step — Quartz가 step을 버린다
        "0 0 10 * * *",       // Quartz 형식인데 일·요일 둘 다 '*'
        "61 9 * * *",         // 분 범위 밖
    })
    @DisplayName("변환할 수 없거나 유효하지 않은 표현식은 INVALID_CRON_EXPRESSION")
    void toQuartz_invalid_throws(String cron) {
        assertThatThrownBy(() -> CronConverter.toQuartz(cron))
            .isInstanceOfSatisfying(CustomException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION));
    }
}
