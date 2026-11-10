package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Gmail 대화 — {@link GmailMessagesOptionSource}와 같은 규칙. 값은 대화 ID, 이름은 대화의 첫 메일 기준. */
@Component
@RequiredArgsConstructor
public class GmailThreadsOptionSource implements OptionSource {

    private static final String THREADS_URL = "https://gmail.googleapis.com/gmail/v1/users/me/threads";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.gmail_threads";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return GmailMessagesOptionSource.fetchPage(googleApiReader, userId, THREADS_URL, "threads", inputs, cursor,
            thread -> thread.path("messages").path(0));
    }
}
