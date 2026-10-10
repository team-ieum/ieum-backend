package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * GitHub 공급원의 호출 모양·login 판정·페이징·오류 매핑을 고정한다 (노드 카탈로그 ② BE-b, spec §6.3).
 * 요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다 — 요청이 나가면 테스트가 깨진다.
 */
class GitHubOptionSourcesTest {

    private static final String BASE = "https://api.github.com";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GitHubTokenProvider tokenProvider = mock(GitHubTokenProvider.class);
    private final GitHubApiReader reader = new GitHubApiReader(tokenProvider, restTemplate);
    private final GitHubOwnersOptionSource owners = new GitHubOwnersOptionSource(reader);
    private final GitHubReposOptionSource repos = new GitHubReposOptionSource(reader);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private void connected() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.of("gh-token"));
    }

    private void expectUser(String login) {
        server.expect(requestTo(BASE + "/user"))
            .andExpect(header("Authorization", "Bearer gh-token"))
            .andExpect(header("Accept", "application/vnd.github+json"))
            .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
            .andRespond(withSuccess("{\"login\":\"" + login + "\"}", MediaType.APPLICATION_JSON));
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("공급원 키는 agent 카탈로그 optionsSource와 1:1 계약")
    void keys() {
        assertThat(List.of(owners.key(), repos.key())).containsExactly("github.owners", "github.repos");
    }

    // ──────────────────────────── owners ────────────────────────────

    @Test
    @DisplayName("owners — 본인 login + 소속 org, 값=라벨=login, 대소문자만 다른 중복은 한 번")
    void ownersAreLoginPlusOrgs() {
        connected();
        expectUser("dobee");
        server.expect(requestTo(startsWith(BASE + "/user/orgs?")))
            .andExpect(queryParam("per_page", "100"))
            .andRespond(withSuccess("[{\"login\":\"ieum-team\"},{\"login\":\"Dobee\"}]", MediaType.APPLICATION_JSON));

        OptionPage page = owners.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("dobee", "dobee"), new OptionItem("ieum-team", "ieum-team"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("org 조회가 403(App에 Members 권한 없음 등)이면 본인 login만 돌려준다 — 개인 저장소 흐름을 막지 않는다")
    void orgForbiddenDegradesToLoginOnly() {
        connected();
        expectUser("dobee");
        server.expect(requestTo(startsWith(BASE + "/user/orgs?"))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(owners.fetch(userId, Map.of(), null).items())
            .containsExactly(new OptionItem("dobee", "dobee"));
    }

    @Test
    @DisplayName("org 조회가 5xx면 삼키지 않고 GITHUB_API_UNAVAILABLE")
    void orgServerErrorPropagates() {
        connected();
        expectUser("dobee");
        server.expect(requestTo(startsWith(BASE + "/user/orgs?")))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertErrorCode(() -> owners.fetch(userId, Map.of(), null), ErrorCode.GITHUB_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("GitHub 토큰이 없으면(미연동) HTTP 호출 없이 ACCOUNT_NOT_CONNECTED")
    void noTokenMeansNotConnected() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.empty());

        assertErrorCode(() -> owners.fetch(userId, Map.of(), null), ErrorCode.ACCOUNT_NOT_CONNECTED);
        assertErrorCode(() -> repos.fetch(userId, Map.of("owner", "dobee"), null), ErrorCode.ACCOUNT_NOT_CONNECTED);
    }

    // ──────────────────────────── repos ────────────────────────────

    @Test
    @DisplayName("repos — owner가 본인 login(대소문자 무시)이면 /user/repos?affiliation=owner(비공개 포함)")
    void reposOfOwnLoginUseUserRepos() {
        connected();
        expectUser("dobee");
        server.expect(requestTo(startsWith(BASE + "/user/repos?")))
            .andExpect(queryParam("affiliation", "owner"))
            .andExpect(queryParam("sort", "updated"))
            .andExpect(queryParam("per_page", "50"))
            .andExpect(queryParam("page", "1"))
            .andRespond(withSuccess("[{\"name\":\"ieum-backend\"},{\"name\":\"notes\"}]", MediaType.APPLICATION_JSON));

        OptionPage page = repos.fetch(userId, Map.of("owner", "Dobee"), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("ieum-backend", "ieum-backend"), new OptionItem("notes", "notes"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("repos — 다른 owner는 /orgs/{owner}/repos, Link rel=next가 있으면 다음 페이지 번호가 nextCursor")
    void reposOfOrgUseOrgReposAndPaging() {
        connected();
        expectUser("dobee");
        server.expect(requestTo(startsWith(BASE + "/orgs/ieum-team/repos?")))
            .andExpect(queryParam("sort", "updated"))
            .andExpect(queryParam("per_page", "50"))
            .andExpect(queryParam("page", "2"))
            .andRespond(withSuccess("[{\"name\":\"agent\"}]", MediaType.APPLICATION_JSON)
                .header("Link", "<https://api.github.com/organizations/1/repos?page=3>; rel=\"next\", "
                    + "<https://api.github.com/organizations/1/repos?page=9>; rel=\"last\""));

        OptionPage page = repos.fetch(userId, Map.of("owner", "ieum-team"), "2");

        assertThat(page.items()).containsExactly(new OptionItem("agent", "agent"));
        assertThat(page.nextCursor()).isEqualTo("3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<missing>", "", "  ", "../user", "a/b", "ieum team", "a?x=1", "toolong-0123456789-0123456789-0123456789"})
    @DisplayName("owner가 없거나 형식이 틀리면 토큰 조회·HTTP 호출 전에 INVALID_INPUT — URL 경로에 쓰는 값이라 막는다")
    void invalidOwnerIsRejectedBeforeAnyCall(String raw) {
        Map<String, String> inputs = "<missing>".equals(raw) ? Map.of() : Map.of("owner", raw);

        assertErrorCode(() -> repos.fetch(userId, inputs, null), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(tokenProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "1001", "1.5", ""})
    @DisplayName("cursor는 1~1000 정수만 — 아니면 HTTP 호출 전에 INVALID_INPUT")
    void invalidCursorIsRejected(String cursor) {
        assertErrorCode(() -> repos.fetch(userId, Map.of("owner", "dobee"), cursor), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    @DisplayName("없는 org(404)는 500이 아니라 INVALID_INPUT")
    void unknownOrgIs400() {
        connected();
        expectUser("dobee");
        server.expect(requestTo(startsWith(BASE + "/orgs/ghost/repos?")))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertErrorCode(() -> repos.fetch(userId, Map.of("owner", "ghost"), null), ErrorCode.INVALID_INPUT);
    }

    // ──────────────────────────── 오류 매핑 ────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "400, INVALID_INPUT",
        "401, AUTHENTICATION_REQUIRED",
        "403, INVALID_INPUT",
        "404, INVALID_INPUT",
        "429, GITHUB_API_UNAVAILABLE",
        "500, GITHUB_API_UNAVAILABLE",
        "503, GITHUB_API_UNAVAILABLE"
    })
    @DisplayName("GitHub 응답 상태를 ErrorCode로 옮긴다")
    void githubErrorMapsToErrorCode(int status, ErrorCode expected) {
        connected();
        server.expect(requestTo(BASE + "/user")).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertErrorCode(() -> owners.fetch(userId, Map.of(), null), expected);
    }

    @Test
    @DisplayName("401은 Google·Notion이 아니라 GitHub 재연동 문구로 나간다")
    void unauthorizedSaysGitHub() {
        connected();
        server.expect(requestTo(BASE + "/user")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> owners.fetch(userId, Map.of(), null))
            .hasMessage("GitHub 계정을 다시 연동해주세요.");
    }

    @Test
    @DisplayName("타임아웃은 GITHUB_API_UNAVAILABLE")
    void timeoutMapsToUnavailable() {
        connected();
        server.expect(requestTo(BASE + "/user"))
            .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertErrorCode(() -> owners.fetch(userId, Map.of(), null), ErrorCode.GITHUB_API_UNAVAILABLE);
    }
}
