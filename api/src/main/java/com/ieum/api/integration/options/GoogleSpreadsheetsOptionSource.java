package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** 사용자 Drive의 스프레드시트 목록 — 최근 수정순. */
@Component
@RequiredArgsConstructor
public class GoogleSpreadsheetsOptionSource implements OptionSource {

    private static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    private static final String SPREADSHEETS_QUERY =
        "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.spreadsheets";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        // 값은 URI 변수로 넣어 엄격 인코딩한다 — cursor에 &·=가 섞여도 파라미터로 새지 않는다.
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(FILES_URL)
            .queryParam("q", "{q}")
            .queryParam("orderBy", "modifiedTime desc")
            .queryParam("pageSize", 50)
            .queryParam("fields", "nextPageToken,files(id,name)")
            .queryParam("supportsAllDrives", true)
            .queryParam("includeItemsFromAllDrives", true);
        Map<String, Object> vars = new HashMap<>(Map.of("q", SPREADSHEETS_QUERY));
        if (cursor != null && !cursor.isBlank()) {
            uri.queryParam("pageToken", "{cursor}");
            vars.put("cursor", cursor);
        }

        JsonNode body = googleApiReader.get(userId, uri.encode().buildAndExpand(vars).toUri(),
            GoogleApiReader.DRIVE_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        body.path("files").forEach(file ->
            items.add(new OptionItem(file.path("id").asText(), file.path("name").asText())));
        return new OptionPage(items, body.path("nextPageToken").asText(null));
    }
}
