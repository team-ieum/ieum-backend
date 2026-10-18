package com.ieum.api.trigger.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.trigger.NodeTestMode;
import com.ieum.workflowcore.trigger.PollStart;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** 요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다 — 요청이 나가면 verify가 깨진다. */
class GitHubIssueSourceTest {

    private static final String BASE = "https://api.github.com";
    private static final String ISSUE = """
        {"number": 7, "title": "버그", "body": null, "html_url": "https://github.com/dobee/ieum/issues/7",
         "user": {"login": "octo"}, "labels": [{"name": "bug"}, {"name": "p1"}],
         "created_at": "2026-10-11T01:02:03Z", "state": "open"}""";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GitHubTokenProvider tokenProvider = mock(GitHubTokenProvider.class);
    private final GitHubIssueSource source =
        new GitHubIssueSource(new GitHubApiReader(tokenProvider, restTemplate));
    private final ObjectMapper json = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private void connected() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.of("gh-token"));
    }

    /** 설치 범위 조회 한 페이지 — {@code per_page=100}·{@code page} 고정, {@code next}면 Link rel="next". */
    private void expectPage(String path, int page, String body, boolean next) {
        HttpHeaders headers = new HttpHeaders();
        if (next) {
            headers.add(HttpHeaders.LINK, "<" + BASE + path + "?page=" + (page + 1) + ">; rel=\"next\"");
        }
        server.expect(requestTo(startsWith(BASE + path + "?")))
            .andExpect(queryParam("per_page", "100"))
            .andExpect(queryParam("page", String.valueOf(page)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).headers(headers));
    }

    /** 가장 흔한 모양 — 설치 1개(id 1)에 레포 1개, 요청 2회. */
    private void expectInstalled(long repoId, String fullName) {
        expectPage("/user/installations", 1, "{\"installations\":[{\"id\":1}]}", false);
        expectPage("/user/installations/1/repositories", 1,
            "{\"repositories\":[{\"id\":" + repoId + ",\"full_name\":\"" + fullName + "\"}]}", false);
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ──────────── normalize ────────────

    @Test
    @DisplayName("정규화 — spec §2.3 필드만, body null은 빈 문자열, labels는 이름, createdAt UTC")
    void normalizeShape() throws Exception {
        Map<String, Object> out = GitHubIssueSource.normalize(json.readTree(ISSUE), "dobee/ieum");

        assertThat(out).containsOnlyKeys(
            "issueNumber", "title", "body", "url", "author", "labels", "repo", "createdAt");
        assertThat(out).containsEntry("issueNumber", 7).containsEntry("title", "버그")
            .containsEntry("body", "").containsEntry("url", "https://github.com/dobee/ieum/issues/7")
            .containsEntry("author", "octo").containsEntry("labels", List.of("bug", "p1"))
            .containsEntry("repo", "dobee/ieum").containsEntry("createdAt", "2026-10-11T01:02:03Z");

        // 깨진 payload(user null — 스키마상 허용, labels·created_at·body 누락)도 예외 없이 같은 8개 키
        assertThat(GitHubIssueSource.normalize(json.readTree("{\"number\": 1, \"user\": null}"), "o/r"))
            .containsOnlyKeys("issueNumber", "title", "body", "url", "author", "labels", "repo", "createdAt")
            .containsEntry("author", "").containsEntry("labels", List.of()).containsEntry("createdAt", "")
            .containsEntry("body", "");
    }

    // ──────────── resolveRepo (App 설치 범위) ────────────

    @Test
    @DisplayName("설치 범위에 있으면 full_name — GET /repositories는 부르지 않는다")
    void resolveRepoReturnsFullName() {
        connected();
        expectInstalled(123, "dobee/ieum");
        assertThat(source.resolveRepo(userId, 123)).isEqualTo("dobee/ieum");
    }

    @Test
    @DisplayName("[Review Focus 6] 설치 범위 밖 레포(공개 레포라 GET /repositories는 200일 것)는 400 — 설치 필요 안내")
    void publicRepoOutsideInstallationIs400() {
        connected();
        expectInstalled(456, "dobee/other");

        assertThatThrownBy(() -> source.resolveRepo(userId, 123)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.getMessage()).isEqualTo(GitHubIssueSource.REPO_NOT_INSTALLED);
        });
    }

    @Test
    @DisplayName("설치 목록·레포 목록 모두 Link next 끝까지 — 두 번째 설치의 두 번째 페이지에서 찾는다")
    void walksAllInstallationsAndPages() {
        connected();
        expectPage("/user/installations", 1, "{\"installations\":[{\"id\":1}]}", true);
        expectPage("/user/installations", 2, "{\"installations\":[{\"id\":2}]}", false);
        expectPage("/user/installations/1/repositories", 1,
            "{\"repositories\":[{\"id\":456,\"full_name\":\"dobee/other\"}]}", false);
        expectPage("/user/installations/2/repositories", 1,
            "{\"repositories\":[{\"id\":789,\"full_name\":\"team/app\"}]}", true);
        expectPage("/user/installations/2/repositories", 2,
            "{\"repositories\":[{\"id\":123,\"full_name\":\"team/ieum\"}]}", false);

        assertThat(source.resolveRepo(userId, 123)).isEqualTo("team/ieum");
    }

    @Test
    @DisplayName("일시중지(suspended_at) 설치는 레포를 조회하지 않고 목록에서 뺀다 — webhook이 오지 않는다")
    void suspendedInstallationIsSkipped() {
        connected();
        expectPage("/user/installations", 1,
            "{\"installations\":[{\"id\":1,\"suspended_at\":\"2026-10-01T00:00:00Z\"},{\"id\":2,\"suspended_at\":null}]}",
            false);
        expectPage("/user/installations/2/repositories", 1,
            "{\"repositories\":[{\"id\":456,\"full_name\":\"team/app\"}]}", false);

        assertThat(new GitHubApiReader(tokenProvider, restTemplate).installedRepos(userId))
            .extracting(item -> item.id()).containsExactly("456");
    }

    @Test
    @DisplayName("요청 상한(20회)에서 멈추고 못 찾으면 400 — fail closed, 21번째 요청은 없다")
    void stopsAtRequestCap() {
        connected();
        expectPage("/user/installations", 1, "{\"installations\":[{\"id\":1}]}", false);
        for (int page = 1; page < GitHubApiReader.INSTALLED_REPOS_MAX_REQUESTS; page++) {
            expectPage("/user/installations/1/repositories", page,
                "{\"repositories\":[{\"id\":456,\"full_name\":\"dobee/other\"}]}", true);
        }

        assertErrorCode(() -> source.resolveRepo(userId, 123), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("설치 목록 조회의 404·403·401은 모두 400 INVALID_INPUT")
    void clientErrorsBecome400() {
        connected();
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED)) {
            server.expect(requestTo(startsWith(BASE + "/user/installations?"))).andRespond(withStatus(status));
        }
        for (int i = 0; i < 3; i++) {
            assertErrorCode(() -> source.resolveRepo(userId, 123), ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("GitHub 장애는 503 그대로, 미연동은 ACCOUNT_NOT_CONNECTED(HTTP 없음)")
    void outageAndNotConnected() {
        connected();
        server.expect(requestTo(startsWith(BASE + "/user/installations?")))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertErrorCode(() -> source.resolveRepo(userId, 123), ErrorCode.GITHUB_API_UNAVAILABLE);

        UUID other = UUID.randomUUID();
        given(tokenProvider.getAccessToken(other)).willReturn(Optional.empty());
        assertErrorCode(() -> source.resolveRepo(other, 123), ErrorCode.ACCOUNT_NOT_CONNECTED);
    }

    // ──────────── latestIssue ────────────

    @Test
    @DisplayName("최근 목록에서 PR을 건너뛰고 첫 이슈를 정규화 — full_name 경로·쿼리 고정")
    void latestIssueSkipsPullRequests() {
        connected();
        expectInstalled(123, "dobee/ieum");
        server.expect(requestTo(startsWith(BASE + "/repos/dobee/ieum/issues?")))
            .andExpect(queryParam("state", "all"))
            .andExpect(queryParam("sort", "created"))
            .andExpect(queryParam("direction", "desc"))
            .andExpect(queryParam("per_page", "20"))
            .andRespond(withSuccess("[{\"number\": 9, \"pull_request\": {}}, " + ISSUE + "]",
                MediaType.APPLICATION_JSON));

        assertThat(source.latestIssue(userId, 123)).get()
            .satisfies(issue -> assertThat(issue).containsEntry("issueNumber", 7).containsEntry("repo", "dobee/ieum"));
    }

    @Test
    @DisplayName("이슈가 하나도 없으면(PR만 있어도) empty")
    void latestIssueEmpty() {
        connected();
        expectInstalled(123, "dobee/ieum");
        server.expect(requestTo(startsWith(BASE + "/repos/dobee/ieum/issues?")))
            .andRespond(withSuccess("[{\"number\": 9, \"pull_request\": {}}]", MediaType.APPLICATION_JSON));

        assertThat(source.latestIssue(userId, 123)).isEmpty();
    }

    // ──────────── TriggerHandler — 구독 ────────────

    private static Map<String, Object> repoConfig(Object repoId) {
        Map<String, Object> config = new HashMap<>();
        config.put("repoId", repoId);
        return config;
    }

    @Test
    @DisplayName("[Review Focus] 구독 스냅샷·resource_key — 숫자 77과 \"77\"은 같은 스냅샷·같은 키, 미설정이면 {repoId: null}·키 없음, 폴링 없음")
    void snapshotAndResourceKey() {
        Map<String, Object> fromNumber = source.snapshot(repoConfig(77));
        Map<String, Object> fromString = source.snapshot(repoConfig("77"));

        assertThat(fromNumber).isEqualTo(Map.of("repoId", 77L)).isEqualTo(fromString);
        assertThat(source.resourceKey(fromNumber)).isEqualTo("77");
        assertThat(source.resourceKey(fromString)).isEqualTo("77");

        Map<String, Object> unset = source.snapshot(repoConfig("  "));
        assertThat(unset).hasSize(1).containsEntry("repoId", null);
        assertThat(source.resourceKey(unset)).isNull();

        assertThat(source.subscribes()).isTrue();
        assertThat(source.pollStart(LocalDateTime.now())).isEqualTo(PollStart.NONE);
        assertErrorCode(() -> source.snapshot(repoConfig("abc")), ErrorCode.INVALID_WORKFLOW);
    }

    @Test
    @DisplayName("[Review Focus 4] 숫자 123과 문자열 \"123\"·\" 123 \"은 같은 repoId")
    void numberAndDigitStringGiveSameKey() {
        assertThat(GitHubIssueSource.repoId(repoConfig(123))).contains(123L);
        assertThat(GitHubIssueSource.repoId(repoConfig(123L))).contains(123L);
        assertThat(GitHubIssueSource.repoId(repoConfig("123"))).contains(123L);
        assertThat(GitHubIssueSource.repoId(repoConfig(" 123 "))).contains(123L);
    }

    @Test
    @DisplayName("repoId가 없거나 빈 문자열이면 미설정(empty) — 저장을 막지 않는다")
    void missingIsEmpty() {
        assertThat(GitHubIssueSource.repoId(repoConfig(null))).isEmpty();
        assertThat(GitHubIssueSource.repoId(repoConfig("  "))).isEmpty();
        assertThat(GitHubIssueSource.repoId(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "12a", "1.5", "99999999999999999999", "owner/name"})
    @DisplayName("양의 정수 문자열이 아니면 INVALID_WORKFLOW")
    void malformedIsRejected(String raw) {
        assertErrorCode(() -> GitHubIssueSource.repoId(repoConfig(raw)), ErrorCode.INVALID_WORKFLOW);
    }

    @Test
    @DisplayName("숫자형 0·음수·실수·불리언도 거부")
    void malformedNonString() {
        for (Object raw : new Object[] {0, -5L, 1.5d, true}) {
            assertErrorCode(() -> GitHubIssueSource.repoId(repoConfig(raw)), ErrorCode.INVALID_WORKFLOW);
        }
    }

    @Test
    @DisplayName("verifyAccess — 스냅샷 repoId가 설치 범위 밖이면 400")
    void verifyChecksRepo() {
        connected();
        expectInstalled(456, "dobee/other");
        Map<String, Object> snapshot = Map.of("repoId", 123L); // 구독 스냅샷은 {repoId}만(F14)

        assertErrorCode(() -> source.verifyAccess(userId, snapshot), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("verifyAccess — repoId가 비면(작성 중) GitHub을 부르지 않는다")
    void verifySkips() {
        Map<String, Object> blank = new HashMap<>();
        blank.put("repoId", null);
        source.verifyAccess(userId, blank);
        // 기대 요청 0건 — 요청이 나갔으면 verifyRequests가 깨진다
    }

    // ──────────── TriggerHandler 노드 테스트(FETCH) ────────────

    @Test
    @DisplayName("fetchSample — 노드 config의 repoId(문자열)로 설치 범위 확인 후 최근 이슈, repoId가 비면 400(→ NodeTestService가 FAILED 샘플)")
    void fetchSampleUsesNodeConfig() {
        connected();
        expectInstalled(123, "dobee/ieum");
        server.expect(requestTo(startsWith(BASE + "/repos/dobee/ieum/issues?")))
            .andRespond(withSuccess("[" + ISSUE + "]", MediaType.APPLICATION_JSON));

        assertThat(source.type()).isEqualTo(TriggerType.GITHUB_NEW_ISSUE);
        assertThat(source.nodeTestMode()).isEqualTo(NodeTestMode.FETCH);
        assertThat(source.fetchSample(userId, Map.of("repoId", "123"))).isPresent();
        assertErrorCode(() -> source.fetchSample(userId, Map.of("repoId", "")),
            ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("TriggerHandler — type은 GITHUB_NEW_ISSUE, 실행 출력은 심어 둔 정규화 페이로드 그대로(config 미혼입)")
    void triggerHandlerTypeAndOutput() {
        Map<String, Object> planted = Map.of("issueNumber", 7, "title", "버그");
        Node node = new Node("t1", NodeType.TRIGGER, "GitHub", Map.of("repoId", "123"), "github.new_issue");

        assertThat(source.type()).isEqualTo(TriggerType.GITHUB_NEW_ISSUE);
        assertThat(source.output(node, Map.of("repoId", "123"), planted)).isEqualTo(planted);
    }
}
