package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Drive 폴더 목록. 루트 항목은 넣지 않는다 — 필드를 비우면 도구 기본값이 내 드라이브 루트다. */
@Component
@RequiredArgsConstructor
public class GoogleFoldersOptionSource implements OptionSource {

    private static final String FOLDERS_QUERY = "mimeType='application/vnd.google-apps.folder' and trashed=false";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.folders";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return googleApiReader.listDriveFiles(userId, FOLDERS_QUERY, cursor);
    }
}
