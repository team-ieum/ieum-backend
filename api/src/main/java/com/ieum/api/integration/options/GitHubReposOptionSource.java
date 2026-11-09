package com.ieum.api.integration.options;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final GitHubApiReader gitHubApiReader;

    @Override
    public String key() {
        return "github.repos";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        // 형식 검증은 토큰 조회·HTTP 호출보다 먼저다
        String owner = GitHubApiReader.requireOwner(inputs);
        int page = GitHubApiReader.parsePage(cursor);

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
}
