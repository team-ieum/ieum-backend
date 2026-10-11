package com.ieum.api.trigger.github;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.integration.options.GitHubApiReader;
import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class GitHubIssueDispatcherTest {

    private final AppTriggerSubscriptionService subscriptions = mock(AppTriggerSubscriptionService.class);
    private final GitHubIssueSource gitHub = mock(GitHubIssueSource.class);
    private final AppTriggerFirer firer = mock(AppTriggerFirer.class);
    private final GitHubIssueDispatcher dispatcher = new GitHubIssueDispatcher(subscriptions, gitHub, firer);
    private final Map<String, Object> issue = Map.of("issueNumber", 7, "title", "버그", "repo", "dobee/ieum");

    private AppTriggerSubscription subscription(UUID ownerId) {
        AppTriggerSubscription sub = mock(AppTriggerSubscription.class);
        given(sub.getWorkflowId()).willReturn(UUID.randomUUID());
        given(sub.getUserId()).willReturn(ownerId);
        return sub;
    }

    @Test
    @DisplayName("repoId 문자열 키로 구독을 찾아 재검증 후 정규화 출력으로 발화한다")
    void firesMatchingSubscription() {
        UUID owner = UUID.randomUUID();
        AppTriggerSubscription sub = subscription(owner);
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_ISSUE, "123")).willReturn(List.of(sub));

        dispatcher.dispatch(123L, issue);

        verify(gitHub).resolveRepo(owner, 123L);
        verify(firer).fire(sub.getWorkflowId(), TriggerType.GITHUB_NEW_ISSUE, issue);
    }

    @Test
    @DisplayName("[Review Focus 2] 레포 접근을 잃은 소유자는 건너뛰고 같은 레포의 다른 구독은 발화한다")
    void revokedOwnerIsSkippedOthersRun() {
        UUID revoked = UUID.randomUUID();
        AppTriggerSubscription lost = subscription(revoked);
        AppTriggerSubscription kept = subscription(UUID.randomUUID());
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_ISSUE, "123")).willReturn(List.of(lost, kept));
        willThrow(new CustomException(ErrorCode.INVALID_INPUT)).given(gitHub).resolveRepo(revoked, 123L);

        dispatcher.dispatch(123L, issue);

        verify(firer, never()).fire(eq(lost.getWorkflowId()), any(), any());
        verify(firer).fire(kept.getWorkflowId(), TriggerType.GITHUB_NEW_ISSUE, issue);
    }

    @Test
    @DisplayName("[Review Focus 6] App 설치에서 빠진 레포로 수신 — 실제 설치 범위 조회에 repoId가 없으면 실행 없음")
    void uninstalledRepoFiresNothing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GitHubTokenProvider tokenProvider = mock(GitHubTokenProvider.class);
        UUID owner = UUID.randomUUID();
        given(tokenProvider.getAccessToken(owner)).willReturn(Optional.of("gh-token"));
        server.expect(requestTo(startsWith("https://api.github.com/user/installations?")))
            .andRespond(withSuccess("{\"installations\": [{\"id\": 1}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://api.github.com/user/installations/1/repositories?")))
            .andRespond(withSuccess("{\"repositories\": [{\"id\": 456, \"full_name\": \"dobee/other\"}]}",
                MediaType.APPLICATION_JSON));
        AppTriggerSubscription sub = subscription(owner);
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_ISSUE, "123")).willReturn(List.of(sub));
        GitHubIssueDispatcher realCheck = new GitHubIssueDispatcher(subscriptions,
            new GitHubIssueSource(new GitHubApiReader(tokenProvider, restTemplate)), firer);

        realCheck.dispatch(123L, issue);

        server.verify();
        verify(firer, never()).fire(any(), any(), any());
    }
}
