package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 워크시트 1행 헤더로 열 목록을 만든다. FieldSource·OptionSource 공용 — 열 범위 규칙은
 * agent {@code tools/google_sheets.py}와 같아야 한다(spec).
 */
@Component
@RequiredArgsConstructor
class GoogleSheetColumnsReader {

    static final String UNTITLED = "(제목 없음)";

    private static final String SPREADSHEET_URL = "https://sheets.googleapis.com/v4/spreadsheets/{id}";
    private static final String VALUES_URL = SPREADSHEET_URL + "/values/{range}";
    private static final Pattern SPREADSHEET_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern SHEET_ID = Pattern.compile("^\\d{1,10}$");
    private static final int MAX_BLANK_COLUMNS = 26;

    private final GoogleApiReader googleApiReader;

    /** header는 빈 헤더면 {@link #UNTITLED}, 헤더 행이 전부 비었으면 null */
    record Column(String letter, String header) {
    }

    List<Column> columns(UUID userId, Map<String, String> inputs) {
        // URL 경로에 끼우는 값이라 계정·토큰 조회보다 먼저 검사한다.
        String spreadsheetId = requireId(inputs, "spreadsheet_id", "스프레드시트", SPREADSHEET_ID);
        long sheetId = Long.parseLong(requireId(inputs, "sheet_id", "워크시트", SHEET_ID));

        JsonNode meta = googleApiReader.get(userId,
            UriComponentsBuilder.fromUriString(SPREADSHEET_URL)
                .queryParam("fields", "sheets.properties(sheetId,title,gridProperties.columnCount)")
                .buildAndExpand(spreadsheetId).encode().toUri(),
            GoogleApiReader.SPREADSHEETS_SCOPE, GoogleApiReader.DRIVE_SCOPE);

        JsonNode properties = null;
        for (JsonNode sheet : meta.path("sheets")) {
            // Google은 기본값 0인 sheetId를 생략할 수 있다 — 없으면 0으로 읽는다.
            if (sheet.path("properties").path("sheetId").asLong() == sheetId) {
                properties = sheet.path("properties");
                break;
            }
        }
        if (properties == null) {
            throw new CustomException(ErrorCode.GOOGLE_RESOURCE_NOT_FOUND);
        }

        String range = "'" + properties.path("title").asText().replace("'", "''") + "'!1:1";
        JsonNode values = googleApiReader.get(userId,
            // 제목의 '/'까지 인코딩하도록 변수 엄격 인코딩(encode() 먼저)
            UriComponentsBuilder.fromUriString(VALUES_URL).encode().buildAndExpand(spreadsheetId, range).toUri(),
            GoogleApiReader.SPREADSHEETS_SCOPE, GoogleApiReader.DRIVE_SCOPE);

        List<String> headers = new ArrayList<>();
        values.path("values").path(0).forEach(cell -> headers.add(cell.asText()));
        int last = headers.size() - 1;
        while (last >= 0 && headers.get(last).isBlank()) {
            last--;
        }

        List<Column> columns = new ArrayList<>();
        if (last < 0) {
            int count = Math.min(properties.path("gridProperties").path("columnCount").asInt(MAX_BLANK_COLUMNS),
                MAX_BLANK_COLUMNS);
            for (int i = 0; i < count; i++) {
                columns.add(new Column(columnLetter(i), null));
            }
            return columns;
        }
        for (int i = 0; i <= last; i++) {
            String header = headers.get(i);
            columns.add(new Column(columnLetter(i), header.isBlank() ? UNTITLED : header));
        }
        return columns;
    }

    /** 0-based 인덱스 → A…Z, AA, AB…(bijective base-26) */
    static String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        for (int n = index; n >= 0; n = n / 26 - 1) {
            sb.insert(0, (char) ('A' + n % 26));
        }
        return sb.toString();
    }

    /** strip → 빈 값 → 참조식 → 형식 순으로 거절(INVALID_INPUT). name은 메시지 속 대상 이름 */
    private static String requireId(Map<String, String> inputs, String key, String name, Pattern format) {
        String id = inputs.get(key) == null ? "" : inputs.get(key).strip();
        if (id.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, name + "를 먼저 골라 주세요.");
        }
        if (id.contains("{{")) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "변수 참조로 지정된 " + name + "는 실행 전이라 불러올 수 없어요.");
        }
        if (!format.matcher(id).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, name + " ID 형식이 올바르지 않습니다.");
        }
        return id;
    }
}
