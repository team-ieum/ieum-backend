package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class NumberFormatsTest {

    private static final ZonedDateTime NOW =
        ZonedDateTime.now(Clock.fixed(Instant.parse("2026-11-07T04:30:00Z"), ConditionValues.KST));

    private static Object out(String event, Object... kv) {
        Map<String, Object> in = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            in.put((String) kv[i], kv[i + 1]);
        }
        return Formatter.apply("formatter." + event, in, NOW).get("output");
    }

    private static void fails(String event, Object... kv) {
        assertThatThrownBy(() -> out(event, kv)).isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("number_math: 사칙연산·부호, 지수 표기 없음, 0 나누기·값 없음·못 읽는 값은 실패")
    void numberMath() {
        assertThat(out("number_math", "input", "1,000원", "operation", "add", "value", "2.5")).isEqualTo("1002.5");
        assertThat(out("number_math", "input", "10", "operation", "subtract", "value", "3")).isEqualTo("7");
        assertThat(out("number_math", "input", "1000", "operation", "multiply", "value", "1")).isEqualTo("1000");
        assertThat(out("number_math", "input", "10", "operation", "divide", "value", "3")).isEqualTo("3.3333333333");
        fails("number_math", "input", "10", "operation", "divide", "value", "0");
        assertThat(out("number_math", "input", "5", "operation", "negate")).isEqualTo("-5");
        fails("number_math", "input", "5", "operation", "add");
        fails("number_math", "input", "5", "operation", "add", "value", "약 1천");
        assertThatThrownBy(() -> out("number_math", "input", "약 1천", "operation", "add", "value", "1"))
            .isInstanceOf(FormatterFailure.class)
            .hasMessageContaining("계산하기:")
            .hasMessageContaining("숫자로 읽지 못했어요");
        fails("number_math", "input", "5");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("거대 지수 입력은 자릿수 상한으로 실패한다(메모리 폭주 없음)")
    void hugeExponent() {
        fails("number_math", "input", "1e999999999", "operation", "multiply", "value", "1");
        fails("number_math", "input", "1", "operation", "add", "value", "1e-999999999");
        fails("number_math", "input", "9".repeat(900), "operation", "multiply", "value", "9".repeat(900));
        fails("number_format", "input", "1e999999999");
        fails("number_currency", "input", "1e999999999");
    }

    @Test
    @DisplayName("number_format: 기본 쉼표·마침표, 구분 기호 바꾸기, 같은 기호면 실패")
    void numberFormat() {
        assertThat(out("number_format", "input", "1234567.891")).isEqualTo("1,234,567.891");
        assertThat(out("number_format", "input", "1234567.891", "thousands", "period", "decimal", "comma"))
            .isEqualTo("1.234.567,891");
        assertThat(out("number_format", "input", "1234567.891", "thousands", "none")).isEqualTo("1234567.891");
        assertThat(out("number_format", "input", "-1234", "thousands", "space")).isEqualTo("-1 234");
        fails("number_format", "input", "1234567.891", "thousands", "comma", "decimal", "comma");
    }

    @Test
    @DisplayName("number_currency: 통화별 자릿수·기호, 한국식은 원화만")
    void numberCurrency() {
        assertThat(out("number_currency", "input", "12000")).isEqualTo("12,000원");
        assertThat(out("number_currency", "input", "12000", "style", "symbol")).isEqualTo("₩12,000");
        assertThat(out("number_currency", "input", "12000.6", "currency", "KRW")).isEqualTo("12,001원");
        assertThat(out("number_currency", "input", "12000", "currency", "USD", "style", "korean")).isEqualTo("$12,000.00");
        assertThat(out("number_currency", "input", "1500.4", "currency", "JPY")).isEqualTo("¥1,500");
        assertThat(out("number_currency", "input", "12000", "currency", "EUR")).isEqualTo("€12,000.00");
        assertThat(out("number_currency", "input", "-500", "currency", "KRW")).isEqualTo("-500원");
    }

    @Test
    @DisplayName("number_phone: 한국 번호 하이픈·국제·숫자만, 아니면 실패")
    void numberPhone() {
        assertThat(out("number_phone", "input", "01012345678")).isEqualTo("010-1234-5678");
        assertThat(out("number_phone", "input", "+82 10 1234 5678", "format", "international")).isEqualTo("+82 10-1234-5678");
        assertThat(out("number_phone", "input", "02-345-6789")).isEqualTo("02-345-6789");
        assertThat(out("number_phone", "input", "0313456789")).isEqualTo("031-345-6789");
        assertThat(out("number_phone", "input", "15881234")).isEqualTo("1588-1234");
        assertThat(out("number_phone", "input", "010-1234-5678", "format", "digits")).isEqualTo("01012345678");
        fails("number_phone", "input", "12345");
    }

    @Test
    @DisplayName("빈_입력: 숫자 변환 4개 모두 실패")
    void emptyInput() {
        fails("number_math", "input", "", "operation", "negate");
        fails("number_format", "input", "");
        fails("number_currency", "input", "");
        fails("number_phone", "input", "");
    }
}
