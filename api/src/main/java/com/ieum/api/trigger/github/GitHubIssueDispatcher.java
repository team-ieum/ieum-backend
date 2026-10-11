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
 * webhook 202 이후의 일(spec §4.2 5~6) — GitHub 10초 제한 밖에서 구독마다 재검증·발화한다.
 * 비활성·삭제 판정과 실행 생성은 BE-a {@link AppTriggerFirer}가 한다(구독 행은 활성 워크플로우에만 있다 — 동기화가 트랜잭션 안).
 *
 * <p>구독 하나의 실패는 그 구독만 건너뛴다. 로그에 이슈 내용을 싣지 않는다. 재검증의 일시 장애(503·rate limit)는 그 이벤트
 * 유실이다(spec §0 유실 허용 — 재시도 없음). 로그로 권한 회수와 구분한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubIssueDispatcher {

    private final AppTriggerSubscriptionService subscriptionService;
    private final GitHubIssueSource gitHubIssueSource;
    private final AppTriggerFirer appTriggerFirer;

    @Async("workflowExecutor")
    public void dispatch(long repoId, Map<String, Object> issue) {
        for (AppTriggerSubscription subscription : subscriptionService
                .findByResource(TriggerType.GITHUB_NEW_ISSUE, String.valueOf(repoId))) {
            try {
                // 수신 시점 재검증(spec §5) — 저장 때와 같은 App 설치 범위 기준. 레포·설치에서 빠진 소유자에게
                // 계속 전달되지 않게. 실패(400·503)는 예외로 이 구독만 건너뛴다(fail closed)
                gitHubIssueSource.resolveRepo(subscription.getUserId(), repoId);
                appTriggerFirer.fire(subscription.getWorkflowId(), TriggerType.GITHUB_NEW_ISSUE, issue);
            } catch (CustomException e) {
                log.warn("[GitHubIssueDispatcher] 구독 건너뜀 — workflowId: {}, errorCode: {}{}",
                    subscription.getWorkflowId(), e.getErrorCode(),
                    e.getErrorCode() == ErrorCode.GITHUB_API_UNAVAILABLE
                        ? " (일시 장애·rate limit — 이 이벤트 유실, delivery 마커로 Redeliver도 무시됨)" : "");
            } catch (Exception e) {
                log.warn("[GitHubIssueDispatcher] 구독 처리 실패 — workflowId: {}", subscription.getWorkflowId(), e);
            }
        }
    }
}
