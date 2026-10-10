package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GitHub 액션의 {@code owner} 필드 선택지 — 본인 login + 소속 org. 값과 라벨 모두 login이다.
 * 목록은 GitHub App 설치 범위로 한정된다. org 조회가 거부되면(App에 Organization Members 권한이 없는 경우 등)
 * 본인 login만 돌려준다 — 개인 저장소 흐름을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubOwnersOptionSource implements OptionSource {

    private final GitHubApiReader gitHubApiReader;

    @Override
    public String key() {
        return "github.owners";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String login = gitHubApiReader.get(userId, List.of("user"), Map.of()).body().path("login").asText();

        List<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(login, login));
        try {
            JsonNode orgs = gitHubApiReader.get(userId, List.of("user", "orgs"), Map.of("per_page", "100")).body();
            orgs.forEach(org -> {
                String orgLogin = org.path("login").asText("");
                if (!orgLogin.isBlank() && !orgLogin.equalsIgnoreCase(login)) {
                    items.add(new OptionItem(orgLogin, orgLogin));
                }
            });
        } catch (CustomException e) {
            // 4xx(권한 부족 등)만 삼킨다 — 401·5xx·타임아웃은 사용자 조치나 재시도가 필요한 실패라 전파한다.
            if (e.getErrorCode() != ErrorCode.INVALID_INPUT) {
                throw e;
            }
            log.warn("[GitHubOwnersOptionSource] org 목록 조회 거부 — 본인 login만 반환 "
                + "(GitHub App의 Organization Members 권한·설치 범위 확인)");
        }
        return new OptionPage(items, null);
    }
}
