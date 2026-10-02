package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
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
 * 스프레드시트의 워크시트(탭) 목록. 저장값은 탭 제목이다 — agent Sheets 도구가 A1 표기
 * ({@code '<제목>'!A1})로 쓰기 때문이다. 한 번에 전부 오므로 페이지가 없다.
 */
@Component
@RequiredArgsConstructor
public class GoogleWorksheetsOptionSource implements OptionSource {

    private static final String SPREADSHEET_URL = "https://sheets.googleapis.com/v4/spreadsheets/{id}";
    private static final Pattern SPREADSHEET_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.worksheets";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String spreadsheetId = inputs.get("spreadsheet_id");
        // URL 경로에 끼우는 값이라 형식 검사로 경로 주입을 막는다. 계정·토큰 조회보다 먼저 한다.
        if (spreadsheetId == null || !SPREADSHEET_ID.matcher(spreadsheetId).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "spreadsheet_id가 없거나 형식이 올바르지 않습니다.");
        }

        JsonNode body = googleApiReader.get(userId,
            UriComponentsBuilder.fromUriString(SPREADSHEET_URL)
                .queryParam("fields", "sheets.properties.title")
                .buildAndExpand(spreadsheetId).encode().toUri(),
            GoogleApiReader.SPREADSHEETS_SCOPE, GoogleApiReader.DRIVE_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        body.path("sheets").forEach(sheet -> {
            String title = sheet.path("properties").path("title").asText();
            items.add(new OptionItem(title, title));
        });
        return new OptionPage(items, null);
    }
}
