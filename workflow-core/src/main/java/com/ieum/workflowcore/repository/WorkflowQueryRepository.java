package com.ieum.workflowcore.repository;

import static com.ieum.workflowcore.domain.QWorkflow.workflow;
import static com.ieum.workflowcore.domain.QWorkflowExecution.workflowExecution;
import static com.ieum.workflowcore.domain.QWorkflowExecutionLog.workflowExecutionLog;
import static com.ieum.workflowcore.domain.QWorkflowVersion.workflowVersion;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkflowQueryRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 사용자의 활성/비활성 워크플로우 개수를 조회합니다.
     */
    public long countWorkflowsByActiveStatus(UUID userId, boolean isActive) {
        Long count = queryFactory
            .select(workflow.count())
            .from(workflow)
            .where(
                workflow.userId.eq(userId),
                workflow.isActive.eq(isActive)
            )
            .fetchOne();
        return count != null ? count : 0L;
    }

    /**
     * 사용자의 워크플로우 중 가장 최근 실행 상태가 FAILED인 워크플로우 개수를 조회합니다.
     */
    public long countErroredWorkflows(UUID userId) {
        com.ieum.workflowcore.domain.QWorkflowExecution subExec = 
            new com.ieum.workflowcore.domain.QWorkflowExecution("subExec");

        Long count = queryFactory
            .select(workflow.count())
            .from(workflow)
            .where(
                workflow.userId.eq(userId),
                JPAExpressions
                    .select(subExec.status)
                    .from(subExec)
                    .where(subExec.workflow.eq(workflow))
                    .orderBy(subExec.startedAt.desc())
                    .limit(1)
                    .eq(ExecutionStatus.FAILED)
            )
            .fetchOne();
        return count != null ? count : 0L;
    }

    /**
     * 사용자의 워크플로우 실행 중 현재 RUNNING 상태인 실행 개수를 조회합니다.
     */
    public long countRunningExecutions(UUID userId) {
        Long count = queryFactory
            .select(workflowExecution.count())
            .from(workflowExecution)
            .join(workflowExecution.workflow, workflow)
            .where(
                workflow.userId.eq(userId),
                workflowExecution.status.eq(ExecutionStatus.RUNNING)
            )
            .fetchOne();
        return count != null ? count : 0L;
    }

    /**
     * 특정 기간 내의 총 실행 횟수를 조회합니다. (오늘/어제 통계용)
     */
    public long countExecutionsInPeriod(UUID userId, LocalDateTime start, LocalDateTime end) {
        Long count = queryFactory
            .select(workflowExecution.count())
            .from(workflowExecution)
            .join(workflowExecution.workflow, workflow)
            .where(
                workflow.userId.eq(userId),
                workflowExecution.startedAt.goe(start),
                workflowExecution.startedAt.loe(end)
            )
            .fetchOne();
        return count != null ? count : 0L;
    }

    /**
     * 특정 기간 내 완료된 실행 목록을 조회합니다. (성공률, 평균 시간 연산용)
     */
    public List<WorkflowExecution> findExecutionsInPeriod(UUID userId, LocalDateTime start, LocalDateTime end) {
        return queryFactory
            .selectFrom(workflowExecution)
            .join(workflowExecution.workflow, workflow).fetchJoin()
            .where(
                workflow.userId.eq(userId),
                workflowExecution.startedAt.goe(start),
                workflowExecution.startedAt.loe(end)
            )
            .orderBy(workflowExecution.startedAt.asc())
            .fetch();
    }

    /**
     * 특정 기간 동안의 시간별 실행 횟수를 조회합니다.
     * 결과는 [시간(Integer), 횟수(Long)] 튜플 목록입니다.
     */
    public List<Tuple> findHourlyExecutionCounts(UUID userId, LocalDateTime start, LocalDateTime end) {
        return queryFactory
            .select(
                workflowExecution.startedAt.hour(),
                workflowExecution.count()
            )
            .from(workflowExecution)
            .join(workflowExecution.workflow, workflow)
            .where(
                workflow.userId.eq(userId),
                workflowExecution.startedAt.goe(start),
                workflowExecution.startedAt.loe(end)
            )
            .groupBy(workflowExecution.startedAt.hour())
            .fetch();
    }

    /**
     * 사용자의 모든 워크플로우에 대한 최근 실행 이력을 페이징 조회합니다.
     */
    public List<WorkflowExecution> findRecentExecutions(UUID userId, int page, int size) {
        return queryFactory
            .selectFrom(workflowExecution)
            .join(workflowExecution.workflow, workflow).fetchJoin()
            .where(workflow.userId.eq(userId))
            .orderBy(workflowExecution.startedAt.desc())
            .offset((long) page * size)
            .limit(size)
            .fetch();
    }

    /**
     * 최근 실행 이력 조회 시 다음 페이지가 존재하는지 여부를 확인합니다.
     */
    public boolean hasNextRecentExecutions(UUID userId, int page, int size) {
        List<WorkflowExecution> result = queryFactory
            .selectFrom(workflowExecution)
            .join(workflowExecution.workflow, workflow)
            .where(workflow.userId.eq(userId))
            .orderBy(workflowExecution.startedAt.desc())
            .offset((long) (page + 1) * size)
            .limit(1)
            .fetch();
        return !result.isEmpty();
    }

    /**
     * 사용자의 실패한 실행 목록과 실패 원인이 된 노드 로그(에러 메시지)를 함께 조회합니다.
     */
    public List<Tuple> findFailedExecutionsWithErrors(UUID userId, int page, int size) {
        return queryFactory
            .select(
                workflowExecution,
                workflowExecutionLog.nodeId,
                workflowExecutionLog.nodeType,
                workflowExecutionLog.errorMessage
            )
            .from(workflowExecution)
            .join(workflowExecution.workflow, workflow).fetchJoin()
            .leftJoin(workflowExecutionLog).on(
                workflowExecutionLog.execution.eq(workflowExecution)
                .and(workflowExecutionLog.status.eq(ExecutionLogStatus.FAILED))
            )
            .where(
                workflow.userId.eq(userId),
                workflowExecution.status.eq(ExecutionStatus.FAILED)
            )
            .orderBy(workflowExecution.startedAt.desc())
            .offset((long) page * size)
            .limit(size)
            .fetch();
    }

    /**
     * 주어진 MongoDB 정의 ID({@code mongoDefinitionId}) 집합 중, 각 워크플로우의 <b>최신 버전</b>
     * 이면서 해당 사용자 소유인 워크플로우를 페이징 조회합니다. (연동 서비스별 워크플로우 목록 2단계)
     *
     * <p>반환 Tuple = [Workflow, WorkflowVersion(최신)]. WorkflowVersion.mongoDefinitionId로
     * usedNodeCount를 매핑합니다. 정렬은 워크플로우 생성일 내림차순.
     *
     * <p>다음 페이지 존재 여부 판별을 위해 {@code size + 1}개를 조회한다. 별도 count 쿼리 없이
     * 호출자가 {@code 결과 크기 > size}로 {@code hasNext}를 판단하고 초과분을 잘라낸다.
     */
    public List<Tuple> findOwnedLatestVersions(UUID userId, List<String> mongoDefinitionIds, int page, int size) {
        return queryFactory
            .select(workflow, workflowVersion)
            .from(workflowVersion)
            .join(workflowVersion.workflow, workflow)
            .where(
                workflow.userId.eq(userId),
                workflowVersion.mongoDefinitionId.in(mongoDefinitionIds),
                isLatestVersion()
            )
            .orderBy(workflow.createdAt.desc())
            .offset((long) page * size)
            .limit(size + 1L)
            .fetch();
    }

    /**
     * 현재 행의 WorkflowVersion이 그 워크플로우의 최신(최대 version) 버전인지 판별하는 조건식.
     */
    private com.querydsl.core.types.dsl.BooleanExpression isLatestVersion() {
        com.ieum.workflowcore.domain.QWorkflowVersion subVersion =
            new com.ieum.workflowcore.domain.QWorkflowVersion("subVersion");
        return workflowVersion.version.eq(
            JPAExpressions
                .select(subVersion.version.max())
                .from(subVersion)
                .where(subVersion.workflow.eq(workflow))
        );
    }

    /**
     * 실패한 실행 목록 조회 시 다음 페이지가 존재하는지 여부를 확인합니다.
     */
    public boolean hasNextFailedExecutions(UUID userId, int page, int size) {
        List<WorkflowExecution> result = queryFactory
            .selectFrom(workflowExecution)
            .join(workflowExecution.workflow, workflow)
            .where(
                workflow.userId.eq(userId),
                workflowExecution.status.eq(ExecutionStatus.FAILED)
            )
            .orderBy(workflowExecution.startedAt.desc())
            .offset((long) (page + 1) * size)
            .limit(1)
            .fetch();
        return !result.isEmpty();
    }
}
