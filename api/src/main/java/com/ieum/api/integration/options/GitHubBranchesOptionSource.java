package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GitHub 액션의 브랜치 칸({@code head}·{@code base}·{@code branch}) 선택지 — 입력 {@code owner}·{@code repo}의 브랜치,
 * 값과 라벨은 브랜치 이름. q 없으면 {@code /branches} 20개씩(cursor = 페이지 번호), q가 있으면
 * {@code /git/matching-refs/heads/<q>} 접두 일치 앞 20개(nextCursor null).
 */
@Component
@RequiredArgsConstructor
public class GitHubBranchesOptionSource implements OptionSource {

    private static final String HEADS_PREFIX = "refs/heads/";

    private final GitHubApiReader gitHubApiReader;

    @Override
    public String key() {
        return "github.branches";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        // 형식 검증은 토큰 조회·HTTP 호출보다 먼저다
        String owner = GitHubApiReader.requireOwner(inputs);
        String repo = GitHubApiReader.requireRepo(inputs);
        String q = GitHubApiReader.searchText(inputs);
        int page = GitHubApiReader.parsePage(cursor);

        if (q != null) {
            return prefixMatch(userId, owner, repo, q);
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("per_page", String.valueOf(GitHubApiReader.RESOURCE_PAGE_SIZE));
        query.put("page", String.valueOf(page));
        GitHubApiReader.Page response = gitHubApiReader.get(userId, List.of("repos", owner, repo, "branches"), query);

        List<OptionItem> items = new ArrayList<>();
        response.body().forEach(branch -> {
            String name = branch.path("name").asText("");
            if (!name.isBlank()) {
                items.add(new OptionItem(name, name));
            }
        });
        return new OptionPage(items, response.hasNext() ? String.valueOf(page + 1) : null);
    }

    private OptionPage prefixMatch(UUID userId, String owner, String repo, String q) {
        List<String> path = new ArrayList<>(List.of("repos", owner, repo, "git", "matching-refs", "heads"));
        // 세그먼트마다 따로 인코딩된다 — 빈·.·.. 세그먼트만 막으면 다른 API 경로로 새지 않는다
        for (String segment : q.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "검색어가 올바르지 않습니다.");
            }
            path.add(segment);
        }

        List<OptionItem> items = new ArrayList<>();
        for (JsonNode ref : gitHubApiReader.get(userId, path, Map.of()).body()) {
            String name = ref.path("ref").asText("");
            if (name.startsWith(HEADS_PREFIX) && name.length() > HEADS_PREFIX.length()) {
                name = name.substring(HEADS_PREFIX.length());
                items.add(new OptionItem(name, name));
                if (items.size() == GitHubApiReader.RESOURCE_PAGE_SIZE) {
                    break;
                }
            }
        }
        return new OptionPage(items, null);
    }
}
