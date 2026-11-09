package com.ieum.api.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.executor.ApprovalFailure;
import com.ieum.workflowcore.engine.executor.ApprovalGateConfig;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 게이트별 결정(승인·거절·값 제출). 게이트마다 결정을 받아 게이트 행에 출력을 남기고, 마지막 게이트가
 * 결정되면 게이트마다 자기 출력으로 {@code node_runs} 행을 쓴 뒤 이어간다({@link #proceed}).
 *
 * <p>잠금 순서: 게이트의 실행 ID만 스칼라로 읽고 → 실행 행을 잠그고 → 그 뒤에 게이트 엔티티를 읽는다. 잠금 전에
 * 엔티티를 읽으면 영속성 컨텍스트에 남은 낡은 {@code decision}을 보고 같은 게이트를 두 번 처리한다.
 *
 * <p>출력은 {@link SensitiveDataMasker}를 거쳐 게이트 행·{@code node_runs}에 같은 값으로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalGateService {

    static final String APPROVED = "approved";
    static final String REJECTED = "rejected";
    static final String SUBMITTED = "submitted";
    static final String TIMEOUT = "timeout";
    static final String EXPIRED_REASON = "승인 만료";
    static final String INACTIVE_REASON = "워크플로우가 꺼져 있어 이어가지 않았어요";

    private final ApprovalGateRepository approvalGateRepository;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowCrudService workflowCrudService;
    private final ExecutionRetryService executionRetryService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private record Locked(WorkflowExecution execution, ApprovalGate gate, ApprovalGateConfig config) {
    }

    /** @param content editable 게이트에서 고친 검토 내용(선택). 키는 items key, 값은 문자열 */
    @Transactional
    public WorkflowExecutionResponse approve(UUID userId, UUID gateId, String note, Map<String, Object> content) {
        requireNote(note);
        Locked l = lock(userId, gateId, ApprovalGateConfig.REQUEST);
        Map<String, Object> output = approvedOutput(userId, l.config(), note, content);
        decide(l.gate(), APPROVED, output);
        return proceed(l.execution(), false);
    }

    @Transactional
    public WorkflowExecutionResponse reject(UUID userId, UUID gateId, String note) {
        requireNote(note);
        Locked l = lock(userId, gateId, ApprovalGateConfig.REQUEST);
        Map<String, Object> output = rejectedOutput(userId, l.config(), note);
        decide(l.gate(), REJECTED, output);
        if (!l.config().continueOnReject()) {
            return stop(l.execution(), List.of(l.gate()), note);
        }
        return proceed(l.execution(), false);
    }

    @Transactional
    public WorkflowExecutionResponse submit(UUID userId, UUID gateId, Map<String, Object> values, String note) {
        requireNote(note);
        Locked l = lock(userId, gateId, ApprovalGateConfig.COLLECT);
        Map<String, Object> coerced;
        try {
            requireScalars(values);
            coerced = ApprovalGateConfig.coerce(l.config().fields(), values);
        } catch (ApprovalFailure e) {
            throw new CustomException(ErrorCode.APPROVAL_INPUT_INVALID, e.getMessage());
        }
        Map<String, Object> output = reviewer(userId, note);
        output.put("decision", SUBMITTED);
        output.put("values", coerced);
        decide(l.gate(), SUBMITTED, output);
        return proceed(l.execution(), false);
    }

    /**
     * 레거시 {@code /approve}·{@code /reject} — 남은 게이트 전부에 같은 결정을 내린다. 남은 게이트에 값 입력받기가
     * 있으면 409. 거절은 남은 게이트 중 하나라도 stop이면 전부 FAILED 행 + 실행 FAILED, 전부 continue면 이어간다.
     */
    @Transactional
    public WorkflowExecutionResponse decideAll(UUID userId, UUID executionId, boolean approve, String note) {
        requireNote(note);
        WorkflowExecution original = workflowExecutionService.lockExecutionWithVersion(executionId);
        workflowCrudService.getWorkflowByOwner(userId, original.getWorkflow().getId());
        requireWaiting(original);
        List<ApprovalGate> open = pending(executionId);
        List<ApprovalGateConfig> configs = open.stream().map(this::config).toList();
        if (configs.stream().anyMatch(c -> ApprovalGateConfig.COLLECT.equals(c.event()))) {
            throw new CustomException(ErrorCode.APPROVAL_GATE_ACTION_MISMATCH,
                "값 입력받기 게이트가 남아 있어 한꺼번에 처리할 수 없습니다.");
        }
        for (int i = 0; i < open.size(); i++) {
            Map<String, Object> output = approve
                ? approvedOutput(userId, configs.get(i), note, null)
                : rejectedOutput(userId, configs.get(i), note);
            decide(open.get(i), approve ? APPROVED : REJECTED, output);
        }
        if (!approve && configs.stream().anyMatch(c -> !c.continueOnReject())) {
            return stop(original, open, note);
        }
        return proceed(original, false);
    }

    /**
     * 승인 기한 만료 — sweeper가 부른다. 잠근 뒤 {@code WAITING_APPROVAL}이 아니면 끝. 게이트 행이 없으면(레거시) 기한이
     * 지났을 때 승인 만료로 닫는다. 기한 지난 미결정 게이트 중 멈춤이 있으면 승인 만료로 닫고(알림 있음), 전부 계속이면
     * {@code decision:"timeout"}으로 결정해 끝 판정으로 넘긴다(비활성이면 닫는다). 기한 지난 미결정 게이트가 없으면
     * 기한만 남은 게이트로 다시 잡는다.
     */
    @Transactional
    public void expire(UUID executionId, LocalDateTime now) {
        WorkflowExecution original = workflowExecutionService.lockExecutionWithVersion(executionId);
        if (original.getStatus() != ExecutionStatus.WAITING_APPROVAL) return;
        List<ApprovalGate> gates = approvalGateRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
        if (gates.isEmpty()) {
            if (original.getApprovalDeadline() != null && original.getApprovalDeadline().isBefore(now)) {
                workflowExecutionService.markAsFailed(executionId, EXPIRED_REASON, ExecutionStatus.WAITING_APPROVAL);
            }
            return;
        }
        List<ApprovalGate> expired = gates.stream()
            .filter(g -> !g.isDecided() && g.getDeadline().isBefore(now)).toList();
        if (expired.isEmpty()) {
            reschedule(original, gates.stream().filter(g -> !g.isDecided()).toList());
            return;
        }
        List<ApprovalGateConfig> configs = expired.stream().map(this::config).toList();
        if (configs.stream().anyMatch(c -> !c.continueOnTimeout())) {
            workflowExecutionService.markAsFailed(executionId, EXPIRED_REASON, ExecutionStatus.WAITING_APPROVAL);
            return;
        }
        for (int i = 0; i < expired.size(); i++) {
            decide(expired.get(i), TIMEOUT, timeoutOutput(configs.get(i)));
        }
        proceed(original, true);
    }

    private Locked lock(UUID userId, UUID gateId, String event) {
        UUID executionId = approvalGateRepository.findExecutionIdById(gateId)
            .orElseThrow(() -> new CustomException(ErrorCode.APPROVAL_GATE_NOT_FOUND));
        WorkflowExecution execution = workflowExecutionService.lockExecutionWithVersion(executionId);
        ApprovalGate gate = approvalGateRepository.findById(gateId)
            .filter(g -> g.getUserId().equals(userId))
            .orElseThrow(() -> new CustomException(ErrorCode.APPROVAL_GATE_NOT_FOUND));
        requireWaiting(execution);
        if (gate.isDecided()) {
            throw new CustomException(ErrorCode.APPROVAL_GATE_ALREADY_DECIDED);
        }
        ApprovalGateConfig config = config(gate);
        if (!event.equals(config.event())) {
            throw new CustomException(ErrorCode.APPROVAL_GATE_ACTION_MISMATCH);
        }
        return new Locked(execution, gate, config);
    }

    /**
     * 끝 판정 — 결정 뒤 남은 게이트가 있으면 기한만 다시 잡고 원 실행을 응답한다. 전부 결정됐으면 게이트마다 자기
     * 출력으로 SUCCESS 행을 쓰고, 기다리기가 함께 걸렸으면 DELAY 분기(재개 시각 전이면 {@code WAITING_DELAY})를
     * 탄 뒤 이어간다. 멈춤 결정(거절 stop·만료 stop)은 여기 오지 않는다.
     *
     * @param closeIfInactive sweeper 경로 — 이어가기 직전에 워크플로우가 꺼져 있으면 비활성 문구로 닫는다(알림 있음).
     *                        사용자 결정 경로는 false라 {@code startContinuation}의 INVALID_WORKFLOW로 롤백된다.
     *                        검사는 DELAY 분기 뒤라 재개 시각이 미래면 기다리기로 넘어간다.
     */
    private WorkflowExecutionResponse proceed(WorkflowExecution original, boolean closeIfInactive) {
        List<ApprovalGate> gates = approvalGateRepository.findByExecutionIdOrderByCreatedAtAsc(original.getId());
        List<ApprovalGate> open = gates.stream().filter(g -> !g.isDecided()).toList();
        if (!open.isEmpty()) {
            reschedule(original, open);
            return WorkflowExecutionResponse.from(original, nodeIds(open));
        }
        for (ApprovalGate g : gates) {
            workflowExecutionService.recordGateDecision(
                original, g.getNodeId(), ExecutionLogStatus.SUCCESS, read(g.getOutputJson()), null);
        }
        if (!original.waitingDelayNodeMap().isEmpty()) {
            if (original.getResumeAt() != null && original.getResumeAt().isAfter(LocalDateTime.now())) {
                original.waitForDelay();
                log.info("[ApprovalGateService] 게이트 전부 결정 — 재개 시각 전이라 기다리기로 남김. executionId: {}",
                    original.getId());
                return WorkflowExecutionResponse.from(original, List.of());
            }
            workflowExecutionService.recordDelayRelease(original, Instant.now());
        }
        Workflow workflow = original.getWorkflow();
        if (closeIfInactive && !workflow.isActive()) {
            workflowExecutionService.markAsFailed(original.getId(), INACTIVE_REASON, ExecutionStatus.WAITING_APPROVAL);
            log.info("[ApprovalGateService] 비활성 워크플로우라 이어가지 않음 — executionId: {}", original.getId());
            return WorkflowExecutionResponse.from(original, List.of());
        }
        original.complete();
        WorkflowExecution continuation = executionRetryService.startContinuation(workflow, original);
        log.info("[ApprovalGateService] 게이트 전부 결정 — originalId: {}, continuationId: {}",
            original.getId(), continuation.getId());
        return WorkflowExecutionResponse.from(continuation, List.of());
    }

    /** 거절(멈춤) — 이번에 거절한 게이트에만 FAILED 행, 실행 FAILED. 사용자 결정이라 알림 없음. */
    private WorkflowExecutionResponse stop(WorkflowExecution original, List<ApprovalGate> rejected, String note) {
        String reason = text(note);
        String errorMessage = reason == null ? "승인 거부" : "승인 거부: " + reason;
        for (ApprovalGate g : rejected) {
            workflowExecutionService.recordGateDecision(
                original, g.getNodeId(), ExecutionLogStatus.FAILED, read(g.getOutputJson()), errorMessage);
        }
        original.fail();
        original.recordError(errorMessage);
        log.info("[ApprovalGateService] 거절(멈춤) — executionId: {}", original.getId());
        return WorkflowExecutionResponse.from(original, List.of());
    }

    private static void reschedule(WorkflowExecution original, List<ApprovalGate> open) {
        open.stream().map(ApprovalGate::getDeadline).min(Comparator.naturalOrder())
            .ifPresent(original::rescheduleApprovalDeadline);
    }

    /** 만료(계속) 출력 — 승인 요청은 {@code approved:false}·원래 content, 값 입력받기는 렌더된 기본값. */
    private static Map<String, Object> timeoutOutput(ApprovalGateConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", TIMEOUT);
        if (ApprovalGateConfig.COLLECT.equals(config.event())) {
            output.put("values", config.defaultValues());
        } else {
            output.put("approved", false);
            output.put("content", config.contentValues());
        }
        return output;
    }

    private static void requireNote(String note) {
        String n = text(note);
        if (n != null && n.length() > ApprovalGateConfig.MAX_NOTE) {
            throw new CustomException(ErrorCode.APPROVAL_INPUT_INVALID, "메모는 200자 이하여야 해요");
        }
    }

    private Map<String, Object> approvedOutput(UUID userId, ApprovalGateConfig config, String note,
                                               Map<String, Object> content) {
        Map<String, Object> merged = config.contentValues();
        if (content != null && !content.isEmpty()) {
            if (!config.editable()) {
                throw new CustomException(ErrorCode.APPROVAL_INPUT_INVALID, "이 승인 요청은 내용을 고칠 수 없어요");
            }
            content.forEach((key, value) -> {
                if (!merged.containsKey(key)) {
                    throw new CustomException(ErrorCode.APPROVAL_INPUT_INVALID, "알 수 없는 항목이에요: '" + key + "'");
                }
                if (!(value instanceof String s) || s.length() > ApprovalGateConfig.MAX_ITEM_VALUE) {
                    throw new CustomException(ErrorCode.APPROVAL_INPUT_INVALID,
                        "'" + key + "' 값은 10,000자 이하 글자여야 해요");
                }
                merged.put(key, s);
            });
        }
        Map<String, Object> output = reviewer(userId, note);
        output.put("approved", true);
        output.put("decision", APPROVED);
        output.put("content", merged);
        return output;
    }

    private Map<String, Object> rejectedOutput(UUID userId, ApprovalGateConfig config, String note) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", false);
        output.put("decision", REJECTED);
        output.put("rejectedBy", userId.toString());
        String reason = text(note);
        if (reason != null) {
            output.put("reason", reason);
            output.put("note", reason);
        }
        output.put("reviewer", reviewerOf(userId));
        output.put("content", config.contentValues());
        return output;
    }

    /** 승인·제출 공통 — {@code approvedBy}·{@code approvedAt}·{@code reviewer}·{@code note}(있을 때만). */
    private Map<String, Object> reviewer(UUID userId, String note) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approvedBy", userId.toString());
        output.put("approvedAt", Instant.now().toString());
        output.put("reviewer", reviewerOf(userId));
        String n = text(note);
        if (n != null) output.put("note", n);
        return output;
    }

    private Map<String, Object> reviewerOf(UUID userId) {
        Map<String, Object> reviewer = new LinkedHashMap<>();
        reviewer.put("via", "web");
        reviewer.put("id", userId.toString());
        reviewer.put("name", userRepository.findById(userId).map(User::getName).orElse(null));
        return reviewer;
    }

    private static void requireScalars(Map<String, Object> values) {
        if (values == null) return;
        values.forEach((key, value) -> {
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new ApprovalFailure("'" + key + "' 값은 글자·숫자·예/아니오여야 해요");
            }
        });
    }

    private void decide(ApprovalGate gate, String decision, Map<String, Object> output) {
        gate.decide(decision, write(SensitiveDataMasker.mask(output)), LocalDateTime.now());
    }

    private List<ApprovalGate> pending(UUID executionId) {
        return approvalGateRepository.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
            .filter(g -> !g.isDecided()).toList();
    }

    private ApprovalGateConfig config(ApprovalGate gate) {
        return ApprovalGateConfig.parse(gate.getEvent(), read(gate.getViewJson()));
    }

    private static List<String> nodeIds(List<ApprovalGate> gates) {
        return gates.stream().map(ApprovalGate::getNodeId).toList();
    }

    private static void requireWaiting(WorkflowExecution execution) {
        if (execution.getStatus() != ExecutionStatus.WAITING_APPROVAL) {
            throw new CustomException(ErrorCode.EXECUTION_NOT_WAITING_APPROVAL,
                "현재 상태: " + execution.getStatus());
        }
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "승인 게이트를 읽지 못했습니다.");
        }
    }

    private String write(Map<String, Object> output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "승인 결정을 기록하지 못했습니다.");
        }
    }
}
