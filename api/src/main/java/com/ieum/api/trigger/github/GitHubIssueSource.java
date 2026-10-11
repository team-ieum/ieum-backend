package com.ieum.api.trigger.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.api.trigger.AppTriggerSampleFetcher;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.AppTriggerAccessVerifier;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * GITHUB_NEW_ISSUE 트리거의 GitHub 쪽 — 정규화(런타임 webhook과 노드 테스트가 공유), 레포 접근 검증(IDOR 방지), 최근 이슈 조회.
 *
 * <p>트랜잭션을 걸지 않는다. {@link #verify}는 BE-a sync의 저장 트랜잭션 안에서 불리지만, 토큰 갱신은
 * {@code GitHubTokenService.getValidAccessToken}이 {@code REQUIRES_NEW}로 따로 커밋한다 — 검증 거부(400)·장애(503)의
 * 롤백에 회전된 refresh token이 휩쓸리지 않는다.
 *
 * <p>{@code @Primary} — 스캔 순서와 무관하게 {@code StubAppTriggerAccessVerifier}를 이긴다(다른 포트 구현과 동일).
 */
@Component
@Primary
@RequiredArgsConstructor
public class GitHubIssueSource implements AppTriggerAccessVerifier, AppTriggerSampleFetcher {

    static final String REPO_NOT_INSTALLED =
        "이 레포에 GitHub 앱 설치가 필요합니다. GitHub App 설치 범위에 저장소를 추가해 주세요.";
    // ponytail: 최근 20건이 전부 PR이면 "항목 없음" — 더 필요하면 페이지를 넘긴다
    static final int RECENT_SCAN = 20;
    private static final Pattern FULL_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final GitHubApiReader gitHubApiReader;

    /**
     * 소유자 토큰의 App 설치 범위({@link GitHubApiReader#installedRepos} — 드롭다운과 같은 조회)에 repoId가 있으면 full_name.
     * 없으면 400 {@link #REPO_NOT_INSTALLED}. 목록 조회의 401은 400으로(GitHub 재연동 문구 유지), 그 밖 4xx 400·장애 503·미연동 400은 그대로.
     */
    public String resolveRepo(UUID userId, long repoId) {
        List<OptionItem> installed;
        try {
            installed = gitHubApiReader.installedRepos(userId);
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.AUTHENTICATION_REQUIRED) {
                throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
            }
            throw e;
        }
        String id = String.valueOf(repoId);
        return installed.stream()
            .filter(repo -> repo.id().equals(id) && FULL_NAME.matcher(repo.name()).matches())
            .map(OptionItem::name)
            .findFirst()
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT, REPO_NOT_INSTALLED));
    }

    /** 노드 테스트용 최근 이슈 1건(PR 제외). 접근 검증을 겸한다. */
    public Optional<Map<String, Object>> latestIssue(UUID userId, long repoId) {
        String fullName = resolveRepo(userId, repoId);
        String[] ownerAndName = fullName.split("/", 2);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("state", "all");
        query.put("sort", "created");
        query.put("direction", "desc");
        query.put("per_page", String.valueOf(RECENT_SCAN));
        JsonNode issues = gitHubApiReader
            .get(userId, List.of("repos", ownerAndName[0], ownerAndName[1], "issues"), query).body();
        for (JsonNode issue : issues) {
            // 목록 API는 PR도 이슈로 준다 — pull_request 키가 있으면 PR이다
            if (!issue.has("pull_request")) {
                return Optional.of(normalize(issue, fullName));
            }
        }
        return Optional.empty();
    }

    /** spec §2.3 GitHub 출력. webhook {@code payload.issue}와 목록 API 항목이 같은 모양이라 함수 하나로 충분하다. */
    public static Map<String, Object> normalize(JsonNode issue, String repoFullName) {
        List<String> labels = new ArrayList<>();
        issue.path("labels").forEach(label -> {
            String name = label.path("name").asText("");
            if (!name.isBlank()) {
                labels.add(name);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueNumber", issue.path("number").asInt());
        out.put("title", issue.path("title").asText(""));
        out.put("body", issue.path("body").isTextual() ? issue.path("body").asText() : "");
        out.put("url", issue.path("html_url").asText(""));
        out.put("author", issue.path("user").path("login").asText(""));
        out.put("labels", labels);
        out.put("repo", repoFullName);
        out.put("createdAt", toUtc(issue.path("created_at").asText("")));
        return out;
    }

    /**
     * BE-a 포트 — 저장·활성화 시점 검증(spec §5). BE-a {@code AppTriggerSubscriptionService.sync}가 저장 트랜잭션 안에서 부르고,
     * 같은 행·같은 repoId 재저장에는 부르지 않는다(GitHub 장애가 무관한 편집을 막지 않게). 거부 예외는 그대로 나가 저장 전체를
     * 롤백한다. 수신 시점 재검증은 {@link GitHubIssueDispatcher}가 따로 한다.
     *
     * @param snapshot BE-a 구독 스냅샷 {@code {repoId: Long|null}} — null이면 작성 중(검증 없음, 행은 resource_key null)
     */
    @Override
    public void verify(UUID userId, TriggerType triggerType, Map<String, Object> snapshot) {
        if (triggerType == TriggerType.GITHUB_NEW_ISSUE && snapshot.get("repoId") instanceof Long repoId) {
            resolveRepo(userId, repoId);
        }
    }

    @Override
    public TriggerType triggerType() {
        return TriggerType.GITHUB_NEW_ISSUE;
    }

    /** BE-a 포트 — 노드 테스트 최근 1건. {@code config}는 노드 config 원본(repoId 문자열·숫자). */
    @Override
    public Optional<Map<String, Object>> fetchLatest(UUID userId, Map<String, Object> config) {
        long repoId = AppTriggerSubscriptionService.githubRepoId(config)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT, "GitHub 저장소를 먼저 선택해 주세요."));
        return latestIssue(userId, repoId);
    }

    private static String toUtc(String raw) {
        try {
            return Instant.parse(raw).toString();
        } catch (DateTimeParseException e) {
            return raw;
        }
    }
}
