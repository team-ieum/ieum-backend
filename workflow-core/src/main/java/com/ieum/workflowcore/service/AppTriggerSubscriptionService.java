package com.ieum.workflowcore.service;

import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 트리거 구독 행의 동기화·클레임·폴링 기록. Gmail 호출·실행 생성은 api 모듈 몫이고 여기는 행만 다룬다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppTriggerSubscriptionService {

    private final AppTriggerSubscriptionRepository repository;

    /**
     * 기한이 지난 행을 잠가 집고(SKIP LOCKED) {@code now + lease}로 리스를 건 뒤 커밋한다. 처리(외부 호출·실행 생성)는
     * 호출자가 트랜잭션 밖에서 한다 — 실행 생성이 이 트랜잭션에 합류하면 비동기 워커가 미커밋 행을 읽는다.
     */
    @Transactional
    public List<AppTriggerSubscription> claimDue(TriggerType type, LocalDateTime now, int limit, Duration lease) {
        List<AppTriggerSubscription> due = repository.lockDue(type.name(), now, limit);
        LocalDateTime until = now.plus(lease);
        due.forEach(s -> s.lease(until));
        return due;
    }

    /** 폴링 결과 기록. 그 사이 행이 지워졌으면(비활성화·삭제) 아무것도 하지 않는다. */
    @Transactional
    public void recordPoll(UUID id, Map<String, Object> cursor, String error, LocalDateTime nextPollAt) {
        repository.findById(id).ifPresent(s -> s.recordPoll(cursor, error, LocalDateTime.now(), nextPollAt));
    }

    /** BE-81 webhook 라우팅 — {@code (GITHUB_NEW_ISSUE, repoId)}의 활성 구독. */
    public List<AppTriggerSubscription> findByResource(TriggerType type, String resourceKey) {
        return repository.findByTriggerTypeAndResourceKey(type, resourceKey);
    }

    /** 워크플로우 삭제 경로 전용 — 호출자 트랜잭션에 합류한다. */
    @Transactional
    public void deleteByWorkflowId(UUID workflowId) {
        repository.deleteByWorkflowId(workflowId);
    }
}
