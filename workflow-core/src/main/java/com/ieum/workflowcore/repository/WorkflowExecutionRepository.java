package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    @Query("SELECT e FROM WorkflowExecution e JOIN FETCH e.workflow WHERE e.id = :id")
    Optional<WorkflowExecution> findWithWorkflowById(@Param("id") UUID id);

    /**
     * 실행 시점에 고정된 버전까지 즉시 로딩한다. 잡 큐 워커처럼 트랜잭션/영속성 컨텍스트 밖의
     * 백그라운드 스레드에서 버전을 써야 할 때 LazyInitializationException을 피하기 위한 조회다.
     */
    @Query("SELECT e FROM WorkflowExecution e JOIN FETCH e.workflowVersion WHERE e.id = :id")
    Optional<WorkflowExecution> findWithVersionById(@Param("id") UUID id);

    /**
     * 실행 행을 {@code SELECT ... FOR UPDATE}로 잠그고 읽는다. 재처리 중복 가드처럼
     * read-check-act를 하는 쪽이 동시 요청에 링크를 둘 다 null로 읽지 않게 하려는 락이다.
     *
     * <p><b>일부러 조인이 없다.</b> PostgreSQL은 outer join의 nullable 쪽에 {@code FOR UPDATE}를
     * 적용할 수 없어, fetch join 쿼리에 이 락을 붙이면 런타임에 실패한다. 연관까지 필요하면
     * 같은 트랜잭션에서 {@link #findWithVersionById}를 이어 호출해라 — 영속성 컨텍스트가
     * 같아 같은 인스턴스가 돌아온다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM WorkflowExecution e WHERE e.id = :id")
    Optional<WorkflowExecution> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 이 실행을 재처리로 낳은 원본 실행을 찾는다(역방향 조회 — 링크는 원본에만 있다).
     * 재처리로 만들어진 실행이 아니면 비어 있다.
     */
    Optional<WorkflowExecution> findByRetriedByExecutionId(UUID retryExecutionId);

    /**
     * 임계 시각보다 먼저 시작해 아직 {@code RUNNING}인 실행의 ID를 찾는다(고립 실행 sweeper용).
     *
     * <p>엔티티가 아니라 ID만 돌려주는 이유는, sweeper가 후보마다 별도 트랜잭션에서 상태를
     * 확정하기 때문이다 — 한 건이 실패해도 나머지가 롤백되지 않는다.
     */
    @Query("SELECT e.id FROM WorkflowExecution e "
        + "WHERE e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.RUNNING "
        + "AND e.startedAt < :threshold")
    List<UUID> findStuckRunningIds(@Param("threshold") LocalDateTime threshold);

    /**
     * 승인 기한이 지난 {@code WAITING_APPROVAL} 실행의 ID(승인 만료 sweeper용). {@link #findStuckRunningIds}와
     * 같은 이유로 ID만 돌려준다 — 후보마다 {@code markAsFailed}가 별도 트랜잭션에서 잠가 확정한다.
     */
    @Query("SELECT e.id FROM WorkflowExecution e "
        + "WHERE e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL "
        + "AND e.approvalDeadline < :now")
    List<UUID> findExpiredApprovalIds(@Param("now") LocalDateTime now);

    /**
     * 주어진 시각 이후 시작해 아직 주어진 상태에 있는 실행이 있는지 본다(스케줄 발화 겹침 판정용).
     * {@code startedAt} 하한이 있어야 준비만 되고 버려진 고아 PENDING이 판정을 영구히 막지 않는다.
     */
    boolean existsByWorkflowAndTriggerTypeAndStatusInAndStartedAtAfter(
        Workflow workflow, TriggerType triggerType, Collection<ExecutionStatus> statuses,
        LocalDateTime startedAt);

    /**
     * 아직 종료(SUCCESS/FAILED)·승인 대기(WAITING_APPROVAL)가 아닌 실행만 종료 상태로 전이한다.
     * 전이했으면 1, 아니면 0.
     *
     * <p>런타임은 실행 시작 때 읽은 detached 엔티티를 들고 있어 {@code save()}로 종료하면 그사이
     * 다른 종료자(고립 실행 sweeper)가 쓴 상태·{@code error_message}·재처리 링크를 메모리 값으로
     * 덮어쓴다. 판정과 전이를 한 문장의 조건부 UPDATE로 묶고 종료 컬럼만 갱신해 그 경합을 막는다.
     * 승인 대기는 승인·거부·만료만 끝낼 수 있다 — 런타임이 덮지 않게 여기서도 뺀다.
     * bulk UPDATE는 감사 리스너를 거치지 않으므로 {@code updatedAt}을 직접 세팅한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE WorkflowExecution e SET e.status = :status, e.retryExhausted = :retryExhausted, "
        + "e.finishedAt = :now, e.updatedAt = :now "
        + "WHERE e.id = :id AND e.status NOT IN ("
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.SUCCESS, "
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.FAILED, "
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL)")
    int finishIfNotTerminal(@Param("id") UUID id, @Param("status") ExecutionStatus status,
                            @Param("retryExhausted") boolean retryExhausted,
                            @Param("now") LocalDateTime now);

    /**
     * 아직 종료·승인 대기가 아닌 실행만 {@code RUNNING}으로 전이한다. 전이했으면 1, 아니면 0.
     *
     * <p>{@link #finishIfNotTerminal}과 같은 이유다 — 시작 시점에 {@code save()}하면 워커의 종료 확인
     * 이후 sweeper가 확정한 FAILED·{@code error_message}를 RUNNING으로 되돌린다. 재배달된 승인 대기
     * 실행을 RUNNING으로 되살리지 않는 것도 이 조건이다(되살리면 게이트 앞 노드가 다시 돈다).
     * {@code startedAt}은 재처리·회수 재실행에서도 지금으로 덮는다({@code start()}와 같다).
     */
    @Transactional
    @Modifying
    @Query("UPDATE WorkflowExecution e "
        + "SET e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.RUNNING, "
        + "e.startedAt = :now, e.updatedAt = :now "
        + "WHERE e.id = :id AND e.status NOT IN ("
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.SUCCESS, "
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.FAILED, "
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL)")
    int startIfNotTerminal(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * {@code RUNNING}인 실행만 승인 대기로 멈춘다. 멈췄으면 1, 그사이 다른 종료자가 끝냈으면 0.
     *
     * <p>종료 전이와 같은 조건부 UPDATE다 — 메모리 엔티티를 {@code save()}하면 sweeper가 확정한
     * FAILED·사유를 덮는다. {@code waitingNodeIdsJson}은 멈춘 순간 대기 중인 게이트 ID의 JSON 배열.
     */
    @Transactional
    @Modifying
    @Query("UPDATE WorkflowExecution e "
        + "SET e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL, "
        + "e.waitingApprovalNodeIds = :nodeIds, e.approvalDeadline = :deadline, e.updatedAt = :now "
        + "WHERE e.id = :id "
        + "AND e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.RUNNING")
    int pauseForApproval(@Param("id") UUID id, @Param("nodeIds") String waitingNodeIdsJson,
                         @Param("deadline") LocalDateTime deadline, @Param("now") LocalDateTime now);

    List<WorkflowExecution> findByWorkflow(Workflow workflow);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowExecution we WHERE we.workflow = :workflow")
    void deleteByWorkflow(@Param("workflow") Workflow workflow);
}
