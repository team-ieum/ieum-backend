package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사용자 Drive의 Google Forms 목록 — 최근 수정순. scope는 drive 그룹. */
@Component
@RequiredArgsConstructor
public class GoogleFormsOptionSource implements OptionSource {

    private static final String FORMS_QUERY =
        "mimeType='application/vnd.google-apps.form' and trashed=false";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.forms";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return googleApiReader.listDriveFiles(userId, FORMS_QUERY, cursor);
    }
}
