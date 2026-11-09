package com.ieum.api.trigger.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.trigger.NodeTestMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class GitHubPullRequestSourceTest {

    private static final String BASE = "https://api.github.com";
    private static final List<String> KEYS = List.of("owner", "repoName", "repo", "prNumber", "title", "body", "url",
        "author", "action", "draft", "baseBranch", "headBranch", "headSha", "createdAt");

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GitHubTokenProvider tokenProvider = mock(GitHubTokenProvider.class);
    private final GitHubApiReader reader = new GitHubApiReader(tokenProvider, restTemplate);
    private final GitHubPullRequestSource source =
        new GitHubPullRequestSource(reader, new GitHubIssueSource(reader));
    private final ObjectMapper json = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private static String pr(int number, String association, boolean draft) {
        return """
            {"number": %d, "title": "제목", "body": null, "html_url": "https://github.com/o/r/pull/%d",
             "user": {"login": "u"}, "author_association": "%s", "draft": %s,
             "created_at": "2026-11-09T01:02:03Z",
             "base": {"ref": "main", "repo": {"name": "r", "full_name": "o/r", "owner": {"login": "o"}}},
             "head": {"ref": "feat", "sha": "abc"}}""".formatted(number, number, association, draft);
    }

    @Test
    @DisplayName("빈 config면 스냅샷은 repoId null·이벤트 4개 전부·초안 제외 true")
    void snapshot_빈_config면_기본값() {
        Map<String, Object> expected = new HashMap<>();
        expected.put("repoId", null);
        expected.put("actions", List.of("opened", "synchronize", "reopened", "ready_for_review"));
        expected.put("excludeDrafts", true);

        assertThat(source.snapshot(Map.of())).isEqualTo(expected);
    }

    @Test
    @DisplayName("onSynchronize:false면 actions에서 synchronize가 빠진다")
    void snapshot_꺼진_이벤트는_빠진다() {
        assertThat(source.snapshot(Map.of("onSynchronize", false)).get("actions"))
            .isEqualTo(List.of("opened", "reopened", "ready_for_review"));
    }

    @Test
    @DisplayName("초안 제외가 켜져 있으면 초안 PR은 ready_for_review만 통과")
    void accepts_초안_제외() {
        Map<String, Object> snapshot = source.snapshot(Map.of());

        assertThat(GitHubPullRequestSource.accepts(snapshot, "opened", true)).isFalse();
        assertThat(GitHubPullRequestSource.accepts(snapshot, "synchronize", true)).isFalse();
        assertThat(GitHubPullRequestSource.accepts(snapshot, "reopened", true)).isFalse();
        assertThat(GitHubPullRequestSource.accepts(snapshot, "ready_for_review", true)).isTrue();
    }

    @Test
    @DisplayName("스냅샷 actions에 없는 action은 거부")
    void accepts_목록에_없는_action은_거부() {
        Map<String, Object> snapshot = source.snapshot(Map.of("onOpened", false));

        assertThat(GitHubPullRequestSource.accepts(snapshot, "opened", false)).isFalse();
        assertThat(GitHubPullRequestSource.accepts(snapshot, "closed", false)).isFalse();
        assertThat(GitHubPullRequestSource.accepts(snapshot, "synchronize", false)).isTrue();
    }

    @Test
    @DisplayName("webhook·목록 응답 공통 PR JSON → 카탈로그 순서 14키, body null은 빈 문자열")
    void normalize_webhook과_목록_응답이_같은_모양() throws Exception {
        Map<String, Object> out = GitHubPullRequestSource.normalize(json.readTree(pr(5, "MEMBER", false)), "opened");

        assertThat(out.keySet()).containsExactlyElementsOf(KEYS);
        assertThat(out).containsEntry("owner", "o").containsEntry("repo", "o/r").containsEntry("repoName", "r")
            .containsEntry("prNumber", 5).containsEntry("headSha", "abc").containsEntry("body", "")
            .containsEntry("author", "u").containsEntry("action", "opened").containsEntry("draft", false)
            .containsEntry("baseBranch", "main").containsEntry("headBranch", "feat")
            .containsEntry("createdAt", "2026-11-09T01:02:03Z");
    }

    @Test
    @DisplayName("fetchSample — 비멤버·초안 PR을 건너뛰고 첫 멤버 PR을 action opened로")
    void fetchSample_비멤버_초안을_건너뛴다() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.of("gh-token"));
        server.expect(requestTo(startsWith(BASE + "/user/installations?")))
            .andRespond(withSuccess("{\"installations\":[{\"id\":1}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/user/installations/1/repositories?")))
            .andRespond(withSuccess("{\"repositories\":[{\"id\":123,\"full_name\":\"o/r\"}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/repos/o/r/pulls?")))
            .andExpect(queryParam("state", "all"))
            .andExpect(queryParam("sort", "created"))
            .andExpect(queryParam("direction", "desc"))
            .andExpect(queryParam("per_page", "20"))
            .andRespond(withSuccess("[" + pr(1, "CONTRIBUTOR", false) + "," + pr(2, "MEMBER", true) + ","
                + pr(3, "MEMBER", false) + "]", MediaType.APPLICATION_JSON));

        assertThat(source.type()).isEqualTo(TriggerType.GITHUB_NEW_PR);
        assertThat(source.nodeTestMode()).isEqualTo(NodeTestMode.FETCH);
        assertThat(source.fetchSample(userId, Map.of("repoId", "123", "excludeDrafts", true))).get()
            .satisfies(out -> assertThat(out).containsEntry("prNumber", 3).containsEntry("action", "opened"));
    }
}
