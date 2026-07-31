package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<WorkflowExecution> findByWorkflow(Workflow workflow);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowExecution we WHERE we.workflow = :workflow")
    void deleteByWorkflow(@Param("workflow") Workflow workflow);
}
