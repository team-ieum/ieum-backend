package com.ieum.workflowcore.engine.executor;

import static com.ieum.workflowcore.engine.executor.ApprovalGateConfig.COLLECT;
import static com.ieum.workflowcore.engine.executor.ApprovalGateConfig.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalGateConfigTest {

    private static final String RANGE = "제한 시간은 5분~7일이에요";

    private static Map<String, Object> field(String key, String type) {
        Map<String, Object> f = new HashMap<>();
        f.put("key", key);
        f.put("label", key);
        f.put("type", type);
        return f;
    }

    private static Map<String, Object> collect(List<Map<String, Object>> fields) {
        return Map.of("fields", fields);
    }

    @Test
    @DisplayName("빈_config는_기본값")
    void emptyConfigUsesDefaults() {
        ApprovalGateConfig c = ApprovalGateConfig.parse(REQUEST, Map.of());

        assertThat(c.timeout()).isEqualTo(Duration.ofHours(24));
        assertThat(c.continueOnTimeout()).isFalse();
        assertThat(c.continueOnReject()).isFalse();
        assertThat(c.reminder()).isFalse();
        assertThat(c.approveLabel()).isEqualTo("승인");
        assertThat(c.rejectLabel()).isEqualTo("거절");
        assertThat(c.message()).isEqualTo("승인이 필요해요");
        assertThat(c.items()).isEmpty();
    }

    @Test
    @DisplayName("event_null은_승인_요청")
    void nullEventIsRequest() {
        assertThat(ApprovalGateConfig.parse(null, Map.of()).event()).isEqualTo(REQUEST);
    }

    @Test
    @DisplayName("기한_범위_밖은_실패")
    void timeoutOutOfRangeFails() {
        assertThatThrownBy(() -> ApprovalGateConfig.parse(REQUEST, Map.of("timeoutAmount", 4, "timeoutUnit", "minutes")))
            .isInstanceOf(ApprovalFailure.class).hasMessage(RANGE);
        assertThatThrownBy(() -> ApprovalGateConfig.parse(REQUEST, Map.of("timeoutAmount", 8, "timeoutUnit", "days")))
            .isInstanceOf(ApprovalFailure.class).hasMessage(RANGE);

        assertThat(ApprovalGateConfig.parse(REQUEST, Map.of("timeoutAmount", 7, "timeoutUnit", "days")).timeout())
            .isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("단위_목록_밖은_실패")
    void unknownUnitFails() {
        assertThatThrownBy(() -> ApprovalGateConfig.parse(REQUEST, Map.of("timeoutUnit", "weeks")))
            .isInstanceOf(ApprovalFailure.class);
    }

    @Test
    @DisplayName("칸_21개는_실패")
    void twentyOneFieldsFail() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            fields.add(field("f" + i, "short"));
        }
        assertThatThrownBy(() -> ApprovalGateConfig.parse(COLLECT, collect(fields)))
            .isInstanceOf(ApprovalFailure.class);
    }

    @Test
    @DisplayName("키_형식_중복은_실패")
    void badOrDuplicateKeyFails() {
        assertThatThrownBy(() -> ApprovalGateConfig.parse(COLLECT, collect(List.of(field("이메일", "short")))))
            .isInstanceOf(ApprovalFailure.class);
        assertThatThrownBy(() -> ApprovalGateConfig.parse(COLLECT,
                collect(List.of(field("email", "short"), field("email", "long")))))
            .isInstanceOf(ApprovalFailure.class);
    }

    @Test
    @DisplayName("choice는_options_필수")
    void choiceRequiresOptions() {
        assertThatThrownBy(() -> ApprovalGateConfig.parse(COLLECT, collect(List.of(field("pick", "choice")))))
            .isInstanceOf(ApprovalFailure.class);
    }

    @Test
    @DisplayName("렌더_값은_상한에서_자른다")
    void renderedValuesAreClipped() {
        ApprovalGateConfig c = ApprovalGateConfig.parse(REQUEST, Map.of(
            "message", "a".repeat(2_001),
            "items", List.of(Map.of("key", "body", "value", "b".repeat(10_001)))));

        assertThat(c.message()).hasSize(2_001).endsWith("…");
        assertThat(c.items().get(0).value()).hasSize(10_001).endsWith("…");
    }

    @Test
    @DisplayName("coerce_타입대로_변환")
    void coerceConvertsByType() {
        Map<String, Object> pick = field("pick", "choice");
        pick.put("options", "a,b");
        Map<String, Object> must = field("must", "short");
        must.put("required", true);
        List<ApprovalGateConfig.InputField> fields = ApprovalGateConfig.parse(COLLECT, collect(List.of(
            field("n", "number"), field("ok", "boolean"), field("d", "date"), pick, must,
            field("s", "short")))).fields();

        Map<String, Object> out = ApprovalGateConfig.coerce(fields,
            Map.of("n", "1,000", "ok", "true", "d", "2026-11-10", "pick", "a", "must", "x"));
        assertThat((BigDecimal) out.get("n")).isEqualByComparingTo("1000");
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("d")).isEqualTo("2026-11-10T00:00:00+09:00");
        assertThat(out).doesNotContainKey("s");

        assertThatThrownBy(() -> ApprovalGateConfig.coerce(fields, Map.of("must", "x", "pick", "c")))
            .isInstanceOf(ApprovalFailure.class);
        assertThatThrownBy(() -> ApprovalGateConfig.coerce(fields, Map.of()))
            .isInstanceOf(ApprovalFailure.class);
        assertThatThrownBy(() -> ApprovalGateConfig.coerce(fields, Map.of("must", "x", "s", "a".repeat(1_001))))
            .isInstanceOf(ApprovalFailure.class);
        assertThatThrownBy(() -> ApprovalGateConfig.coerce(fields, Map.of("must", "x", "other", "y")))
            .isInstanceOf(ApprovalFailure.class);
    }

    @Test
    @DisplayName("만료_기본값은_변환_실패_칸을_뺀다")
    void defaultValuesSkipUnconvertible() {
        Map<String, Object> n = field("n", "number");
        n.put("default", "abc");
        Map<String, Object> s = field("s", "short");
        s.put("default", "hi");

        Map<String, Object> defaults = ApprovalGateConfig.parse(COLLECT, collect(List.of(n, s))).defaultValues();

        assertThat(defaults).doesNotContainKey("n").containsEntry("s", "hi");
    }

    @Test
    @DisplayName("view_왕복은_같은_설정")
    void viewRoundTrip() {
        Map<String, Object> pick = field("pick", "choice");
        pick.put("options", "a,b");
        pick.put("default", "a");
        pick.put("required", true);
        Map<String, Object> raw = new HashMap<>(collect(List.of(pick, field("memo", "long"))));
        raw.put("message", "골라 주세요");
        raw.put("timeoutAmount", 2);
        raw.put("timeoutUnit", "days");
        raw.put("onTimeout", "continue");
        raw.put("reminder", true);
        raw.put("webhookCredentialId", "11111111-1111-1111-1111-111111111111");
        assertSameConfig(COLLECT, raw);

        Map<String, Object> request = new HashMap<>();
        request.put("items", List.of(Map.of("key", "title", "value", "보고서")));
        request.put("editable", true);
        request.put("approveLabel", "좋아요");
        request.put("rejectLabel", "안 돼요");
        request.put("onReject", "continue");
        assertSameConfig(REQUEST, request);
    }

    private static void assertSameConfig(String event, Map<String, Object> raw) {
        ApprovalGateConfig a = ApprovalGateConfig.parse(event, raw);
        ApprovalGateConfig b = ApprovalGateConfig.parse(event, a.view());

        assertThat(b.event()).isEqualTo(a.event());
        assertThat(b.message()).isEqualTo(a.message());
        assertThat(b.timeout()).isEqualTo(a.timeout());
        assertThat(b.continueOnTimeout()).isEqualTo(a.continueOnTimeout());
        assertThat(b.continueOnReject()).isEqualTo(a.continueOnReject());
        assertThat(b.reminder()).isEqualTo(a.reminder());
        assertThat(b.webhookId()).isEqualTo(a.webhookId());
        assertThat(b.editable()).isEqualTo(a.editable());
        assertThat(b.approveLabel()).isEqualTo(a.approveLabel());
        assertThat(b.rejectLabel()).isEqualTo(a.rejectLabel());
        assertThat(b.items()).isEqualTo(a.items());
        assertThat(b.fields()).isEqualTo(a.fields());
        assertThat(b.view()).isEqualTo(a.view());
    }
}
