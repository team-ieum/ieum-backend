package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** 페이징·상한·오류 매핑은 Task 2 {@code GitHubIssueSourceTest}가 같은 {@code installedRepos}로 고정한다. */
class GitHubInstalledReposOptionSourceTest {

    private static final String BASE = "https://api.github.com";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final GitHubTokenProvider tokenProvider = mock(GitHubTokenProvider.class);
    private final GitHubInstalledReposOptionSource source =
        new GitHubInstalledReposOptionSource(new GitHubApiReader(tokenProvider, restTemplate));
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    @DisplayName("키는 agent trigger.github_new_issue의 optionsSource와 1:1 계약")
    void key() {
        assertThat(source.key()).isEqualTo("github.installed_repos");
    }

    @Test
    @DisplayName("설치 범위 조회 그대로 — id=숫자 repo id 문자열, name=full_name, id 없는 항목은 버린다, 한 페이지에 전부")
    void listsInstalledRepos() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.of("gh-token"));
        server.expect(requestTo(startsWith(BASE + "/user/installations?")))
            .andRespond(withSuccess("{\"installations\": [{\"id\": 1}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/user/installations/1/repositories?")))
            .andRespond(withSuccess("""
                {"repositories": [{"id": 123, "full_name": "dobee/ieum"}, {"full_name": "no/id"},
                                  {"id": 456, "full_name": "team/app"}]}""", MediaType.APPLICATION_JSON));

        OptionPage page = source.fetch(userId, Map.of(), "abc");

        assertThat(page.items()).containsExactly(
            new OptionItem("123", "dobee/ieum"), new OptionItem("456", "team/app"));
        assertThat(page.nextCursor()).isNull();
    }
}
