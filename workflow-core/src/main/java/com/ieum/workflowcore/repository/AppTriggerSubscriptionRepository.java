package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppTriggerSubscriptionRepository extends JpaRepository<AppTriggerSubscription, UUID> {

    List<AppTriggerSubscription> findByWorkflowId(UUID workflowId);

    List<AppTriggerSubscription> findByTriggerTypeAndResourceKey(TriggerType triggerType, String resourceKey);

    /**
     * 기한이 지난 행을 {@code FOR UPDATE SKIP LOCKED}로 잠근다. 네이티브인 이유: Hibernate H2Dialect는 SKIP LOCKED를
     * 지원하지 않는다고 보고 {@code @Lock}+lock.timeout -2를 평문 {@code FOR UPDATE}로 떨어뜨린다 — 이 SQL은 H2·PG 모두 그대로 받는다.
     * 호출자 트랜잭션 안에서만 의미가 있다 — {@code AppTriggerSubscriptionService.claimDue}가 잠근 채 리스를 건다.
     *
     * @param type {@code TriggerType.name()} — 네이티브 쿼리라 enum을 문자열로 넘긴다
     */
    @Query(value = "SELECT * FROM app_trigger_subscriptions WHERE trigger_type = :type AND next_poll_at <= :now"
        + " ORDER BY next_poll_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<AppTriggerSubscription> lockDue(@Param("type") String type, @Param("now") LocalDateTime now,
                                         @Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AppTriggerSubscription s WHERE s.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);
}
