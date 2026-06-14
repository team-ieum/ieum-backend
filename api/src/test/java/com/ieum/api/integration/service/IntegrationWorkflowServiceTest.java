package com.ieum.api.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ieum.api.integration.domain.IntegrationServiceType;
import com.ieum.api.integration.dto.WorkflowSummaryResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService.BrandWorkflowPage;
import com.ieum.workflowcore.service.IntegrationWorkflowQueryService.ServiceWorkflow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationWorkflowServiceTest {

    @Mock
    private IntegrationWorkflowQueryService integrationWorkflowQueryService;

    @InjectMocks
    private IntegrationWorkflowService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("cursor가 null이면 page 0으로 조회하고 도메인을 DTO로 변환한다")
    void getWorkflowsByService_defaultCursor() {
        // given
        Workflow workflow = Workflow.builder().userId(userId).name("디스코드 봇").isActive(true).build();
        given(integrationWorkflowQueryService.findByBrand(userId, "discord", 0, 20))
            .willReturn(new BrandWorkflowPage(List.of(new ServiceWorkflow(workflow, 2)), false));

        // when
        PageResponse<WorkflowSummaryResponse> response =
            service.getWorkflowsByService(userId, IntegrationServiceType.DISCORD, null, 20);

        // then
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getName()).isEqualTo("디스코드 봇");
        assertThat(response.getContent().get(0).getUsedNodeCount()).isEqualTo(2);
        assertThat(response.isHasNext()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("hasNext가 true이면 nextCursor는 다음 page 번호다")
    void getWorkflowsByService_hasNextCursor() {
        // given
        Workflow workflow = Workflow.builder().userId(userId).name("w").isActive(true).build();
        given(integrationWorkflowQueryService.findByBrand(userId, "discord", 0, 20))
            .willReturn(new BrandWorkflowPage(List.of(new ServiceWorkflow(workflow, 1)), true));

        // when
        PageResponse<WorkflowSummaryResponse> response =
            service.getWorkflowsByService(userId, IntegrationServiceType.DISCORD, null, 20);

        // then
        assertThat(response.isHasNext()).isTrue();
        assertThat(response.getNextCursor()).isEqualTo("1");
    }

    @Test
    @DisplayName("숫자가 아닌 cursor는 INVALID_CURSOR 예외를 던진다")
    void getWorkflowsByService_invalidCursor() {
        assertThatThrownBy(() ->
            service.getWorkflowsByService(userId, IntegrationServiceType.DISCORD, "abc", 20))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("빈 문자열 cursor는 첫 페이지(0)로 처리한다")
    void getWorkflowsByService_blankCursor() {
        // given
        given(integrationWorkflowQueryService.findByBrand(userId, "discord", 0, 20))
            .willReturn(new BrandWorkflowPage(List.of(), false));

        // when
        PageResponse<WorkflowSummaryResponse> response =
            service.getWorkflowsByService(userId, IntegrationServiceType.DISCORD, "  ", 20);

        // then — 빈 커서가 page 0으로 조회되어 정상 응답
        assertThat(response.getContent()).isEmpty();
    }

    @Test
    @DisplayName("음수 cursor는 INVALID_CURSOR 예외를 던진다")
    void getWorkflowsByService_negativeCursor() {
        assertThatThrownBy(() ->
            service.getWorkflowsByService(userId, IntegrationServiceType.DISCORD, "-1", 20))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CURSOR);
    }
}
