package com.ieum.api.integration.options;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        String[] segments = q.split("/", -1);
        // 끝의 / 하나는 "그 아래 브랜치"라는 뜻이라 허용한다 — 빈 조각을 빼고 조회한 뒤 이름이 q로 시작하는지로 거른다
        int count = segments.length > 1 && segments[segments.length - 1].isEmpty() ? segments.length - 1 : segments.length;
        for (String segment : Arrays.copyOf(segments, count)) {
            // 공백뿐인 조각은 pathSegment가 조용히 버린다 — 검사한 조각과 보낸 조각이 달라지지 않게 거부
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "검색어가 올바르지 않습니다.");
            }
            path.add(segment);
        }

        // matching-refs는 페이지가 없어 일치하는 ref를 전부 준다 — 트리로 올리지 않고 20개를 모으면 읽기를 멈춘다
        List<OptionItem> items = gitHubApiReader.stream(userId, path, Map.of(), response -> {
            List<OptionItem> found = new ArrayList<>();
            try (JsonParser parser = MAPPER.createParser(response.getBody())) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    return found;
                }
                while (found.size() < GitHubApiReader.RESOURCE_PAGE_SIZE && parser.nextToken() == JsonToken.START_OBJECT) {
                    String name = MAPPER.<JsonNode>readTree(parser).path("ref").asText("");
                    if (name.startsWith(HEADS_PREFIX) && name.length() > HEADS_PREFIX.length()) {
                        name = name.substring(HEADS_PREFIX.length());
                        if (name.startsWith(q)) {
                            found.add(new OptionItem(name, name));
                        }
                    }
                }
            }
            return found;
        });
        return new OptionPage(items, null);
    }
}
