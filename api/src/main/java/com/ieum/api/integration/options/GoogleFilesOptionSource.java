package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@code google_drive_read}가 읽을 수 있는 파일 — Docs·Sheets(export) + 텍스트 계열. 최근 수정순. */
@Component
@RequiredArgsConstructor
public class GoogleFilesOptionSource implements OptionSource {

    // agent tools/google_drive.py의 _EXPORT_MIME_MAP·_TEXT_MIME_PREFIXES와 같은 형식 — 그쪽을 넓히면 여기도.
    // 어긋나도 선택지가 줄어드는 쪽이라 안전하다(목록에 없는 파일은 Custom value로 넣는다).
    private static final String READABLE_FILES_QUERY = "trashed=false and ("
        + "mimeType='application/vnd.google-apps.document'"
        + " or mimeType='application/vnd.google-apps.spreadsheet'"
        + " or mimeType contains 'text/'"
        + " or mimeType='application/json'"
        + " or mimeType='application/xml'"
        + " or mimeType='application/javascript'"
        + " or mimeType='application/x-yaml')";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.files";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return googleApiReader.listDriveFiles(userId, READABLE_FILES_QUERY, cursor);
    }
}
