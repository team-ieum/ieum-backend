package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowWebhookToken;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowWebhookTokenRepository;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService.IssuedToken;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 실제 H2 스키마에서 발급·재발급·조회·삭제를 본다. 컨텍스트 구성은 {@code NodeTestSampleRepositoryTest}와 같다. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
@Transactional
class WorkflowWebhookTokenServiceTest {

    @Autowired private WorkflowWebhookTokenService service;
    @Autowired private WorkflowWebhookTokenRepository tokenRepository;
    @Autowired private WorkflowRepository workflowRepository;

    private Workflow newWorkflow() {
        return workflowRepository.save(
            Workflow.builder().userId(UUID.randomUUID()).name("w").isActive(true).build());
    }

    @Test
    @DisplayName("발급 — 평문은 반환값으로만 나가고 DB에는 SHA-256 hex만 남는다")
    void issueStoresOnlyHash() {
        Workflow workflow = newWorkflow();

        IssuedToken issued = service.issue(workflow);
        tokenRepository.flush(); // 조회는 JDBC라 영속성 컨텍스트를 보지 않는다

        assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}"); // 32바이트 URL-safe Base64, 패딩 없음
        WorkflowWebhookToken stored = tokenRepository.findByWorkflowId(workflow.getId()).orElseThrow();
        assertThat(stored.getTokenHash()).matches("[0-9a-f]{64}").doesNotContain(issued.token());
        assertThat(service.findWorkflowIdByToken(issued.token())).contains(workflow.getId());
        assertThat(service.status(workflow.getId())).isPresent();
    }

    @Test
    @DisplayName("재발급 — 같은 행을 갱신하고 이전 토큰은 더 이상 조회되지 않는다")
    void reissueInvalidatesPreviousToken() {
        Workflow workflow = newWorkflow();
        IssuedToken first = service.issue(workflow);
        tokenRepository.flush();

        IssuedToken second = service.issue(workflow);
        tokenRepository.flush();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(service.findWorkflowIdByToken(first.token())).isEmpty();
        assertThat(service.findWorkflowIdByToken(second.token())).contains(workflow.getId());
        assertThat(tokenRepository.findAll())
            .filteredOn(t -> t.getWorkflowId().equals(workflow.getId())).hasSize(1);
    }

    @Test
    @DisplayName("미발급·빈 토큰·모르는 토큰은 empty")
    void unknownLookupsAreEmpty() {
        Workflow workflow = newWorkflow();
        tokenRepository.flush();

        assertThat(service.status(workflow.getId())).isEmpty();
        assertThat(service.findWorkflowIdByToken(null)).isEmpty();
        assertThat(service.findWorkflowIdByToken(" ")).isEmpty();
        assertThat(service.findWorkflowIdByToken("nope")).isEmpty();
    }

    @Test
    @DisplayName("deleteByWorkflowId는 그 워크플로우의 토큰만 지운다")
    void deleteByWorkflowIdRemovesOnlyThatToken() {
        Workflow mine = newWorkflow();
        Workflow other = newWorkflow();
        IssuedToken mineToken = service.issue(mine);
        IssuedToken otherToken = service.issue(other);
        tokenRepository.flush();

        tokenRepository.deleteByWorkflowId(mine.getId());

        assertThat(service.findWorkflowIdByToken(mineToken.token())).isEmpty();
        assertThat(service.findWorkflowIdByToken(otherToken.token())).contains(other.getId());
    }
}
