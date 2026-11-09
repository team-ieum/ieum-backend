package com.ieum.api.trigger.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitHubPullRequestDispatcherTest {

    private final AppTriggerSubscriptionService subscriptions = mock(AppTriggerSubscriptionService.class);
    private final GitHubIssueSource gitHub = mock(GitHubIssueSource.class);
    private final AppTriggerFirer firer = mock(AppTriggerFirer.class);
    private final GitHubPullRequestDispatcher dispatcher = new GitHubPullRequestDispatcher(subscriptions, gitHub, firer);
    private final Map<String, Object> pr = Map.of("prNumber", 5, "repo", "o/r");

    private AppTriggerSubscription subscription(UUID ownerId, Map<String, Object> config) {
        AppTriggerSubscription sub = mock(AppTriggerSubscription.class);
        given(sub.getWorkflowId()).willReturn(UUID.randomUUID());
        given(sub.getUserId()).willReturn(ownerId);
        given(sub.getConfig()).willReturn(config);
        return sub;
    }

    @Test
    @DisplayName("구독마다 필터 — actions=[opened] 구독·초안 제외 구독 모두 synchronize·draft PR엔 발화하지 않는다")
    void 구독마다_필터() {
        AppTriggerSubscription openedOnly = subscription(UUID.randomUUID(),
            Map.of("repoId", 123L, "actions", List.of("opened"), "excludeDrafts", false));
        AppTriggerSubscription noDrafts = subscription(UUID.randomUUID(),
            Map.of("repoId", 123L, "actions", GitHubPullRequestSource.ACTIONS, "excludeDrafts", true));
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_PR, "123")).willReturn(List.of(openedOnly, noDrafts));

        dispatcher.dispatch(123L, "synchronize", true, pr);

        verify(firer, never()).fire(any(), any(), any());
        verify(gitHub, never()).resolveRepo(any(), anyLong());
    }

    @Test
    @DisplayName("재검증(resolveRepo) 실패한 구독만 건너뛰고 같은 레포의 다른 구독은 발화한다")
    void 재검증_실패한_구독만_건너뜀() {
        Map<String, Object> all = Map.of("repoId", 123L, "actions", GitHubPullRequestSource.ACTIONS, "excludeDrafts", true);
        UUID revoked = UUID.randomUUID();
        AppTriggerSubscription lost = subscription(revoked, all);
        AppTriggerSubscription kept = subscription(UUID.randomUUID(), all);
        given(subscriptions.findByResource(TriggerType.GITHUB_NEW_PR, "123")).willReturn(List.of(lost, kept));
        willThrow(new CustomException(ErrorCode.INVALID_INPUT)).given(gitHub).resolveRepo(revoked, 123L);

        dispatcher.dispatch(123L, "opened", false, pr);

        verify(firer, never()).fire(eq(lost.getWorkflowId()), any(), any());
        verify(firer).fire(kept.getWorkflowId(), TriggerType.GITHUB_NEW_PR, pr);
    }
}
