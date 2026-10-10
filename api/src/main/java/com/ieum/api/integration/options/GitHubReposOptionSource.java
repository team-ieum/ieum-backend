package com.ieum.api.integration.options;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GitHub 액션의 {@code repo} 필드 선택지 — 입력 {@code owner}의 저장소, 최근 갱신순 50개씩. 값과 라벨은 repo name.
 *
 * <p>owner가 본인 login이면 {@code /user/repos?affiliation=owner}(비공개 포함 — {@code /users/{login}/repos}는
 * 공개만 준다), 아니면 {@code /orgs/{owner}/repos}다. login 판정에 {@code GET /user} 한 번이 든다.
 * 목록은 GitHub App 설치 범위로 한정된다. cursor는 다음 페이지 번호다.
 */
@Component
@RequiredArgsConstructor
public class GitHubReposOptionSource implements OptionSource {

    /** GitHub login 규칙(영숫자·하이픈, 최대 39자 — EMU의 밑줄 포함). URL 경로에 쓰므로 이 형식만 받는다. */
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9_-]{1,39}");
    private static final int MAX_PAGE = 1000;

    private final GitHubApiReader gitHubApiReader;

    @Override
    public String key() {
        return "github.repos";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        // 형식 검증은 토큰 조회·HTTP 호출보다 먼저다
        String owner = requireOwner(inputs);
        int page = parsePage(cursor);

        String login = gitHubApiReader.get(userId, List.of("user"), Map.of()).body().path("login").asText("");
        boolean mine = owner.equalsIgnoreCase(login);

        Map<String, String> query = new LinkedHashMap<>();
        if (mine) {
            query.put("affiliation", "owner");
        }
        query.put("sort", "updated");
        query.put("per_page", String.valueOf(GitHubApiReader.PAGE_SIZE));
        query.put("page", String.valueOf(page));

        GitHubApiReader.Page repos = mine
            ? gitHubApiReader.get(userId, List.of("user", "repos"), query)
            : gitHubApiReader.get(userId, List.of("orgs", owner, "repos"), query);

        List<OptionItem> items = new ArrayList<>();
        repos.body().forEach(repo -> {
            String name = repo.path("name").asText("");
            if (!name.isBlank()) {
                items.add(new OptionItem(name, name));
            }
        });
        return new OptionPage(items, repos.hasNext() ? String.valueOf(page + 1) : null);
    }

    private static String requireOwner(Map<String, String> inputs) {
        String owner = inputs.get("owner") == null ? "" : inputs.get("owner").strip();
        if (!OWNER.matcher(owner).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "owner가 없거나 올바르지 않습니다.");
        }
        return owner;
    }

    private static int parsePage(String cursor) {
        if (cursor == null) {
            return 1;
        }
        try {
            int page = Integer.parseInt(cursor.strip());
            if (page >= 1 && page <= MAX_PAGE) {
                return page;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 같은 오류로 처리
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, "cursor가 올바르지 않습니다.");
    }
}
