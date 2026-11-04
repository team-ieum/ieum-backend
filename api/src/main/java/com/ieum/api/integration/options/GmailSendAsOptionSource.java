package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Gmail 보낸 사람 주소(sendAs) 목록. pending 별칭은 발송할 수 없어 뺀다. 기본 주소({@code isDefault})가 맨 앞,
 * 나머지는 API 순서. 한 번에 전부 오므로 페이지가 없다.
 */
@Component
@RequiredArgsConstructor
public class GmailSendAsOptionSource implements OptionSource {

    private static final URI SEND_AS_URL = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/settings/sendAs");

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.gmail_send_as";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        JsonNode body = googleApiReader.get(userId, SEND_AS_URL,
            GoogleApiReader.GMAIL_MODIFY_SCOPE, GoogleApiReader.GMAIL_READONLY_SCOPE);

        List<OptionItem> defaults = new ArrayList<>();
        List<OptionItem> others = new ArrayList<>();
        body.path("sendAs").forEach(alias -> {
            if ("pending".equals(alias.path("verificationStatus").asText())) {
                return;
            }
            String email = alias.path("sendAsEmail").asText();
            String displayName = alias.path("displayName").asText();
            OptionItem item = new OptionItem(email, displayName.isBlank() ? email : displayName + " <" + email + ">");
            (alias.path("isDefault").asBoolean() ? defaults : others).add(item);
        });
        defaults.addAll(others);
        return new OptionPage(defaults, null);
    }
}
