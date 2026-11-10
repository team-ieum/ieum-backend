package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 폼의 최근 응답 — 30일 안, 제출 시각 역순 20개. 값은 응답 ID, 이름은 'yyyy-MM-dd HH:mm(KST) · 이메일'.
 * 한 번에 전부라 cursor는 무시하고 nextCursor는 늘 null.
 */
@Component
@RequiredArgsConstructor
public class GoogleFormResponsesOptionSource implements OptionSource {

    static final String LIGHT_FIELDS = "nextPageToken,responses(responseId,lastSubmittedTime,respondentEmail)";
    static final int RECENT_DAYS = 30;
    static final int LIMIT = 20;

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final GoogleFormsReader formsReader;

    @Override
    public String key() {
        return "google.form_responses";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String formId = GoogleFormsReader.formId(inputs.get("form_id"));
        String filter = "timestamp >= " + Instant.now().minus(RECENT_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<OptionItem> items = formsReader.listResponses(userId, formId, filter, LIGHT_FIELDS).stream()
            // 문자열 비교는 소수 초 유무(…00Z vs …00.123Z)에 틀린다 — Instant로 비교
            .sorted(Comparator.comparing((JsonNode r) -> Instant.parse(r.path("lastSubmittedTime").asText()))
                .reversed()
                .thenComparing(r -> r.path("responseId").asText()))
            .limit(LIMIT)
            .map(GoogleFormResponsesOptionSource::item)
            .toList();
        return new OptionPage(items, null);
    }

    private static OptionItem item(JsonNode response) {
        String name = WHEN.format(Instant.parse(response.path("lastSubmittedTime").asText()));
        String email = response.path("respondentEmail").asText("");
        return new OptionItem(response.path("responseId").asText(), email.isBlank() ? name : name + " · " + email);
    }
}
