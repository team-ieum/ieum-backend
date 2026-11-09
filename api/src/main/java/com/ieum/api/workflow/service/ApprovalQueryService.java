package com.ieum.api.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.ApprovalGateDetailResponse;
import com.ieum.api.workflow.dto.ApprovalGateSummaryResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인 게이트 조회 — 내 대기 목록과 게이트 상세(본인 것만, 결정된 게이트 포함). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalQueryService {

    static final int MAX_SIZE = 50;
    private static final int TITLE_LENGTH = 200;

    private final ApprovalGateRepository approvalGateRepository;
    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    /** 커서는 기존 실행 목록과 같은 페이지 번호 문자열이다. size는 1~50으로 맞춘다. */
    public PageResponse<ApprovalGateSummaryResponse> list(UUID userId, String cursor, int size) {
        int page = parseCursor(cursor);
        int limit = Math.clamp(size, 1, MAX_SIZE);
        // size + 1개를 받아 초과분으로 hasNext를 본다 — offset은 page * size여야 해서 PageRequest를 그대로 못 쓴다.
        Pageable pageable = new PageRequest(page, limit + 1, Sort.unsorted()) {
            @Override
            public long getOffset() {
                return (long) page * limit;
            }
        };
        List<ApprovalGate> gates = approvalGateRepository.findOpenByUser(userId, pageable);
        boolean hasNext = gates.size() > limit;
        List<ApprovalGate> rows = gates.stream().limit(limit).toList();

        Map<UUID, String> names = workflowRepository
            .findAllById(rows.stream().map(ApprovalGate::getWorkflowId).distinct().toList()).stream()
            .collect(Collectors.toMap(Workflow::getId, Workflow::getName));
        List<ApprovalGateSummaryResponse> content = rows.stream()
            .map(g -> ApprovalGateSummaryResponse.from(g, names.get(g.getWorkflowId()), title(g)))
            .toList();
        return PageResponse.of(content, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    public ApprovalGateDetailResponse detail(UUID userId, UUID gateId) {
        ApprovalGate gate = approvalGateRepository.findById(gateId)
            .filter(g -> g.getUserId().equals(userId))
            .orElseThrow(() -> new CustomException(ErrorCode.APPROVAL_GATE_NOT_FOUND));
        String workflowName = workflowRepository.findById(gate.getWorkflowId()).map(Workflow::getName).orElse(null);
        return ApprovalGateDetailResponse.from(gate, workflowName, view(gate));
    }

    private String title(ApprovalGate gate) {
        Object message = view(gate).get("message");
        if (message == null) return null;
        String text = message.toString();
        return text.length() > TITLE_LENGTH ? text.substring(0, TITLE_LENGTH) : text;
    }

    private Map<String, Object> view(ApprovalGate gate) {
        try {
            return objectMapper.readValue(gate.getViewJson(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "승인 게이트를 읽지 못했습니다.");
        }
    }

    /** {@code WorkflowService.parseCursor}와 같은 규칙 — 빈 값은 첫 페이지, 숫자가 아니거나 음수면 400. */
    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        int page;
        try {
            page = Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
        if (page < 0) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
        return page;
    }
}
