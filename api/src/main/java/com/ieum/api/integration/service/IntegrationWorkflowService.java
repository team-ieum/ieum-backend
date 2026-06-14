package com.ieum.api.integration.service;

import com.ieum.api.integration.domain.IntegrationServiceType;
import com.ieum.api.integration.dto.WorkflowSummaryResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService.BrandWorkflowPage;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 레이어 연동 서비스별 워크플로우 조회 서비스.
 *
 * <p>커서 파싱과 DTO 변환만 담당하는 얇은 레이어. 실제 2단계 조회 로직은
 * {@link IntegrationWorkflowQueryService}(workflow-core)에 위치한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationWorkflowService {

    private final IntegrationWorkflowQueryService integrationWorkflowQueryService;

    public PageResponse<WorkflowSummaryResponse> getWorkflowsByService(
            UUID userId, IntegrationServiceType serviceType, String cursor, int size) {

        int page = parseCursor(cursor);
        BrandWorkflowPage result =
            integrationWorkflowQueryService.findByBrand(userId, serviceType.getBrand(), page, size);

        List<WorkflowSummaryResponse> content = result.items().stream()
            .map(sw -> WorkflowSummaryResponse.from(sw.workflow(), sw.usedNodeCount()))
            .toList();

        return PageResponse.of(content, result.hasNext(),
            result.hasNext() ? String.valueOf(page + 1) : null);
    }

    private int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
    }
}
