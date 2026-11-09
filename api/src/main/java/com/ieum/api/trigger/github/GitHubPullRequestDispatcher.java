package com.ieum.api.trigger.github;

import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * pull_request webhook 202 이후의 일 — {@link GitHubIssueDispatcher} 복제(앱 독립). 같은 레포 구독끼리 설정이 다를 수 있어
 * 필터({@link GitHubPullRequestSource#accepts})는 구독 단위다. 걸러진 구독은 로그 없이 건너뛴다. 로그에 PR 내용을 싣지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubPullRequestDispatcher {

    private final AppTriggerSubscriptionService subscriptionService;
    private final GitHubIssueSource gitHubIssueSource;
    private final AppTriggerFirer appTriggerFirer;

    @Async("workflowExecutor")
    public void dispatch(long repoId, String action, boolean draft, Map<String, Object> pr) {
        for (AppTriggerSubscription subscription : subscriptionService
                .findByResource(TriggerType.GITHUB_NEW_PR, String.valueOf(repoId))) {
            try {
                if (!GitHubPullRequestSource.accepts(subscription.getConfig(), action, draft)) {
                    continue;
                }
                // 수신 시점 재검증 — 실패(400·503)는 예외로 이 구독만 건너뛴다(fail closed)
                gitHubIssueSource.resolveRepo(subscription.getUserId(), repoId);
                appTriggerFirer.fire(subscription.getWorkflowId(), TriggerType.GITHUB_NEW_PR, pr);
            } catch (CustomException e) {
                log.warn("[GitHubPullRequestDispatcher] 구독 건너뜀 — workflowId: {}, errorCode: {}{}",
                    subscription.getWorkflowId(), e.getErrorCode(),
                    e.getErrorCode() == ErrorCode.GITHUB_API_UNAVAILABLE
                        ? " (일시 장애·rate limit — 이 이벤트 유실, delivery 마커로 Redeliver도 무시됨)" : "");
            } catch (Exception e) {
                log.warn("[GitHubPullRequestDispatcher] 구독 처리 실패 — workflowId: {}", subscription.getWorkflowId(), e);
            }
        }
    }
}
