package com.ieum.api.trigger.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.api.integration.options.GoogleFormsReader.FormQuestion;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.PollStart;
import com.ieum.workflowcore.trigger.TriggerHandler;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Forms 새 응답(T1 {@code FORMS_NEW_RESPONSE})·새 응답 또는 수정(T2 {@code FORMS_ANY_RESPONSE}) 트리거 핸들러.
 * 한 클래스가 두 종류를 맡고 {@link FormsTriggerConfig}가 빈 2개를 만든다. 폴링은 {@link FormsTriggerSweeper}.
 * Google 호출은 전부 {@link GoogleFormsReader}(drive scope). 트랜잭션을 걸지 않는다(토큰 갱신 저장 유실).
 */
public class FormsTriggerHandler implements TriggerHandler {

    /** 노드 테스트 후보 목록은 시각만 받는다 — 응답 본문 전체를 수천 건 읽지 않게 */
    static final String LIGHT_FIELDS = "nextPageToken,responses(responseId,createTime,lastSubmittedTime)";
    static final Duration SAMPLE_WINDOW = Duration.ofDays(30);

    private final TriggerType type;
    private final GoogleFormsReader reader;

    public FormsTriggerHandler(TriggerType type, GoogleFormsReader reader) {
        this.type = type;
        this.reader = reader;
    }

    /** 노드 config의 form_id — 비었으면 null(작성 중 구독), 있으면 형식 검사(INVALID_INPUT) */
    static String formId(Map<?, ?> config) {
        return config != null && config.get("form_id") instanceof String s && !s.isBlank()
            ? GoogleFormsReader.formId(s) : null;
    }

    @Override
    public TriggerType type() {
        return type;
    }

    /** T2 — 수정된 응답도 다시 발화한다 */
    boolean updates() {
        return type == TriggerType.FORMS_ANY_RESPONSE;
    }

    @Override
    public boolean subscribes() {
        return true;
    }

    /** {@code {formId}} — 문자열이라 JSON 왕복 뒤에도 같다(숫자면 Integer로 돌아와 저장마다 커서가 리셋된다) */
    @Override
    public Map<String, Object> snapshot(Map<?, ?> config) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("formId", formId(config));
        return snapshot;
    }

    @Override
    public String resourceKey(Map<String, Object> snapshot) {
        return snapshot.get("formId") instanceof String s ? s : null;
    }

    /** "지금 이후 응답만"(Gmail과 같다) — 나머지 커서 키는 {@link FormsCursor}가 채운다, 즉시 폴링 */
    @Override
    public PollStart pollStart(LocalDateTime now) {
        return new PollStart(Map.of("afterSec", now.atZone(ZoneId.systemDefault()).toEpochSecond()), now);
    }

    /** 폼을 읽을 수 있는지 forms.get으로 본다. 401을 그대로 던지면 저장 응답이 401이라 FE가 로그아웃시킨다 → 400 */
    @Override
    public void verifyAccess(UUID userId, Map<String, Object> snapshot) {
        if (!(snapshot.get("formId") instanceof String formId)) {
            return;
        }
        try {
            reader.questions(userId, formId);
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.GOOGLE_RESOURCE_NOT_FOUND) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "폼을 찾을 수 없어요. 편집 권한이 있는 폼인지 확인해 주세요.");
            }
            if (e.getErrorCode() == ErrorCode.AUTHENTICATION_REQUIRED) {
                throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
            }
            throw e;
        }
    }

    @Override
    public NodeTestMode nodeTestMode() {
        return NodeTestMode.FETCH;
    }

    /**
     * 최근 30일 중 제출 시각이 가장 늦은 1건. 후보 목록은 시각만 받고({@link #LIGHT_FIELDS}), 고른 건만
     * {@code timestamp >= 그 시각}으로 다시 받아 전체 답을 읽는다. 시각 형식이 어긋난 응답은 건너뛴다.
     */
    @Override
    public Optional<Map<String, Object>> fetchSample(UUID userId, Map<String, Object> config) {
        String formId = formId(config);
        if (formId == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "폼을 먼저 골라 주세요.");
        }
        Instant since = Instant.now().minus(SAMPLE_WINDOW).truncatedTo(ChronoUnit.SECONDS);
        Optional<JsonNode> newest = reader.listResponses(userId, formId, "timestamp >= " + since, LIGHT_FIELDS)
            .stream()
            .filter(r -> instant(r, "lastSubmittedTime") != null)
            .max(Comparator.comparing(r -> instant(r, "lastSubmittedTime")));
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        String responseId = newest.get().path("responseId").asText();
        String submitted = newest.get().path("lastSubmittedTime").asText();
        Optional<JsonNode> full = reader.listResponses(userId, formId, "timestamp >= " + submitted, null).stream()
            .filter(r -> responseId.equals(r.path("responseId").asText()))
            .findFirst();
        if (full.isEmpty()) {
            return Optional.empty();   // 두 조회 사이에 지워졌다
        }
        return Optional.of(output(full.get(), reader.questions(userId, formId)));
    }

    /** 커서 시각 — T1 createTime(연결 전 응답이 수정돼도 발화하지 않게), T2 lastSubmittedTime. 형식 오류면 null(건너뜀) */
    Long eventSec(JsonNode response) {
        Instant at = instant(response, updates() ? "lastSubmittedTime" : "createTime");
        return at == null ? null : at.getEpochSecond();
    }

    /** 중복 키 — T2는 수정마다 새 키라 다시 발화한다 */
    String dedupeKey(JsonNode response) {
        String id = response.path("responseId").asText();
        return updates() ? id + "@" + response.path("lastSubmittedTime").asText() : id;
    }

    /** Forms 필터는 lastSubmittedTime을 본다(실호출 ①) — T1도 같은 필터, createTime으로는 스위퍼가 거른다 */
    String filter(long lowSec) {
        return "timestamp >= " + Instant.ofEpochSecond(lowSec);
    }

    /**
     * 정규화 출력. T2는 끝에 {@code isUpdate} — 수정 안 한 응답도 두 시각의 소수 자릿수가 달라(실호출 ③) 1초 여유를 둔다.
     */
    Map<String, Object> output(JsonNode response, List<FormQuestion> questions) {
        Map<String, Object> out = FormsResponseNormalizer.normalize(response, questions);
        if (updates()) {
            Instant created = instant(response, "createTime");
            Instant submitted = instant(response, "lastSubmittedTime");
            out.put("isUpdate", created != null && submitted != null && submitted.isAfter(created.plusSeconds(1)));
        }
        return out;
    }

    private static Instant instant(JsonNode response, String key) {
        try {
            return response.hasNonNull(key) ? Instant.parse(response.get(key).asText()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
