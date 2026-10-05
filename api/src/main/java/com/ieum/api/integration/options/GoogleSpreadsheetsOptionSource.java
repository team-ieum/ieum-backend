package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사용자 Drive의 스프레드시트 목록 — 최근 수정순. */
@Component
@RequiredArgsConstructor
public class GoogleSpreadsheetsOptionSource implements OptionSource {

    private static final String SPREADSHEETS_QUERY =
        "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.spreadsheets";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return googleApiReader.listDriveFiles(userId, SPREADSHEETS_QUERY, cursor);
    }
}
