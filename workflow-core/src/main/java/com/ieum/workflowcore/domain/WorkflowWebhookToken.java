package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우 웹훅 수신 주소({@code /api/v1/hooks/{token}})의 토큰. 워크플로우당 1개.
 *
 * <p>평문은 저장하지 않는다 — SHA-256 hex만 둔다(고엔트로피 랜덤 토큰이라 솔트·느린 해시가 필요 없다).
 * 재발급은 이 행의 해시를 갈아 끼워 이전 토큰을 즉시 무효화한다.
 *
 * <p>{@code workflowId}는 FK 없는 uuid다 — 워크플로우 삭제 시 {@code WorkflowCrudService.deleteWorkflowInternal}이 명시적으로 지운다.
 */
@Entity
@Table(name = "workflow_webhook_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkflowWebhookToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "workflow_id", columnDefinition = "uuid", nullable = false, unique = true, updatable = false)
    private UUID workflowId;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    public static WorkflowWebhookToken create(UUID workflowId, String tokenHash, LocalDateTime issuedAt) {
        WorkflowWebhookToken token = new WorkflowWebhookToken();
        token.workflowId = workflowId;
        token.reissue(tokenHash, issuedAt);
        return token;
    }

    public void reissue(String tokenHash, LocalDateTime issuedAt) {
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
    }
}
