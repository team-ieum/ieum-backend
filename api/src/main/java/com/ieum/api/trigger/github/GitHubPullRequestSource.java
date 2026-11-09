package com.ieum.api.trigger.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.TriggerHandler;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GITHUB_NEW_PR 트리거 핸들러 — {@link GitHubIssueSource} 모양 복제(앱 독립). 레포 접근 검증은 {@link GitHubIssueSource#resolveRepo}를
 * 재사용한다. 레포 멤버(OWNER·MEMBER·COLLABORATOR)가 연 PR만 반응한다 — 외부인 PR로 소유자 LLM 키가 돌지 않게(spec §4.2).
 */
@Component
@RequiredArgsConstructor
public class GitHubPullRequestSource implements TriggerHandler {

    static final Set<String> MEMBER_ASSOCIATIONS = Set.of("OWNER", "MEMBER", "COLLABORATOR");
    static final List<String> ACTIONS = List.of("opened", "synchronize", "reopened", "ready_for_review");
    /** ACTIONS와 같은 순서의 카탈로그 boolean 칸 */
    private static final List<String> ACTION_KEYS = List.of("onOpened", "onSynchronize", "onReopened", "onReadyForReview");

    private final GitHubApiReader gitHubApiReader;
    private final GitHubIssueSource gitHubIssueSource;

    /** 카탈로그 outputFields 14키·순서. webhook {@code pull_request}와 목록 API 항목 양쪽에 있는 필드만 쓴다 */
    public static Map<String, Object> normalize(JsonNode pr, String action) {
        JsonNode baseRepo = pr.path("base").path("repo");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", baseRepo.path("owner").path("login").asText(""));
        out.put("repoName", baseRepo.path("name").asText(""));
        out.put("repo", baseRepo.path("full_name").asText(""));
        out.put("prNumber", pr.path("number").asInt());
        out.put("title", pr.path("title").asText(""));
        out.put("body", pr.path("body").isTextual() ? pr.path("body").asText() : "");
        out.put("url", pr.path("html_url").asText(""));
        out.put("author", pr.path("user").path("login").asText(""));
        out.put("action", action);
        out.put("draft", pr.path("draft").asBoolean(false));
        out.put("baseBranch", pr.path("base").path("ref").asText(""));
        out.put("headBranch", pr.path("head").path("ref").asText(""));
        out.put("headSha", pr.path("head").path("sha").asText(""));
        out.put("createdAt", toUtc(pr.path("created_at").asText("")));
        return out;
    }

    /** 구독 필터 — action이 스냅샷 actions에 있고, 초안 제외면 초안은 ready_for_review만 통과 */
    public static boolean accepts(Map<String, Object> snapshot, String action, boolean draft) {
        if (!(snapshot.get("actions") instanceof List<?> actions) || !actions.contains(action)) {
            return false;
        }
        boolean excludeDrafts = !Boolean.FALSE.equals(snapshot.get("excludeDrafts"));
        return !(excludeDrafts && draft && !"ready_for_review".equals(action));
    }

    @Override
    public TriggerType type() {
        return TriggerType.GITHUB_NEW_PR;
    }

    @Override
    public boolean subscribes() {
        return true;
    }

    /**
     * 구독 스냅샷 {@code {repoId: Long|null, actions, excludeDrafts}}. FE는 기본값을 저장하지 않으므로 boolean 칸은 키가 없으면
     * 카탈로그 기본값 true — {@code false}일 때만 끈다.
     */
    @Override
    public Map<String, Object> snapshot(Map<?, ?> config) {
        Map<?, ?> source = config == null ? Map.of() : config;
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < ACTIONS.size(); i++) {
            if (!Boolean.FALSE.equals(source.get(ACTION_KEYS.get(i)))) {
                actions.add(ACTIONS.get(i));
            }
        }
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("repoId", GitHubIssueSource.repoId(source).orElse(null));
        snapshot.put("actions", actions);
        snapshot.put("excludeDrafts", !Boolean.FALSE.equals(source.get("excludeDrafts")));
        return snapshot;
    }

    @Override
    public String resourceKey(Map<String, Object> snapshot) {
        Object repoId = snapshot.get("repoId");
        return repoId == null ? null : String.valueOf(repoId);
    }

    @Override
    public void verifyAccess(UUID userId, Map<String, Object> snapshot) {
        if (snapshot.get("repoId") instanceof Long repoId) {
            gitHubIssueSource.resolveRepo(userId, repoId);
        }
    }

    @Override
    public NodeTestMode nodeTestMode() {
        return NodeTestMode.FETCH;
    }

    /** 최근 PR 20건 중 멤버가 연(초안 제외면 초안도 뺀) 첫 건. 설치 범위를 먼저 확인한다 */
    @Override
    public Optional<Map<String, Object>> fetchSample(UUID userId, Map<String, Object> config) {
        long repoId = GitHubIssueSource.repoId(config)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT, "GitHub 저장소를 먼저 선택해 주세요."));
        boolean excludeDrafts = Boolean.TRUE.equals(snapshot(config).get("excludeDrafts"));
        String[] ownerAndName = gitHubIssueSource.resolveRepo(userId, repoId).split("/", 2);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("state", "all");
        query.put("sort", "created");
        query.put("direction", "desc");
        query.put("per_page", String.valueOf(GitHubIssueSource.RECENT_SCAN));
        JsonNode pulls = gitHubApiReader
            .get(userId, List.of("repos", ownerAndName[0], ownerAndName[1], "pulls"), query).body();
        for (JsonNode pr : pulls) {
            if (MEMBER_ASSOCIATIONS.contains(pr.path("author_association").asText(""))
                && !(excludeDrafts && pr.path("draft").asBoolean(false))) {
                return Optional.of(normalize(pr, "opened"));
            }
        }
        return Optional.empty();
    }

    private static String toUtc(String raw) {
        try {
            return Instant.parse(raw).toString();
        } catch (DateTimeParseException e) {
            return raw;
        }
    }
}
