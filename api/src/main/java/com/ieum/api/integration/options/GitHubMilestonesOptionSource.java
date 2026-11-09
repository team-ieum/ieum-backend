package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GitHub 액션의 마일스톤 칸 선택지 — 입력 {@code owner}·{@code repo}의 열린 마일스톤(마감일순), 값은 번호·라벨은 제목.
 * q 없으면 20개씩(cursor = 페이지 번호), q가 있으면 첫 100개에서 제목 부분 일치(대소문자 무시) 앞 20개(nextCursor null).
 */
@Component
@RequiredArgsConstructor
public class GitHubMilestonesOptionSource implements OptionSource {

    private static final int SEARCH_PAGE_SIZE = 100;

    private final GitHubApiReader gitHubApiReader;

    @Override
    public String key() {
        return "github.milestones";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        // 형식 검증은 토큰 조회·HTTP 호출보다 먼저다
        String owner = GitHubApiReader.requireOwner(inputs);
        String repo = GitHubApiReader.requireRepo(inputs);
        String q = GitHubApiReader.searchText(inputs);
        int page = GitHubApiReader.parsePage(cursor);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("state", "open");
        query.put("sort", "due_on");
        query.put("per_page", String.valueOf(q == null ? GitHubApiReader.RESOURCE_PAGE_SIZE : SEARCH_PAGE_SIZE));
        query.put("page", String.valueOf(q == null ? page : 1));
        GitHubApiReader.Page response =
            gitHubApiReader.get(userId, List.of("repos", owner, repo, "milestones"), query);

        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<OptionItem> items = new ArrayList<>();
        for (JsonNode milestone : response.body()) {
            long number = milestone.path("number").asLong(0);
            String title = milestone.path("title").asText("");
            if (number <= 0 || (needle != null && !title.toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            items.add(new OptionItem(String.valueOf(number), title));
            if (needle != null && items.size() == GitHubApiReader.RESOURCE_PAGE_SIZE) {
                break;
            }
        }
        String next = q == null && response.hasNext() ? String.valueOf(page + 1) : null;
        return new OptionPage(items, next);
    }
}
