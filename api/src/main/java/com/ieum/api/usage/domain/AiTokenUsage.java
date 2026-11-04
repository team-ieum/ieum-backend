package com.ieum.api.usage.domain;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 호출 1회 = 1행. 사용량 집계의 유일한 원천이다(IEUM-BE-98).
 *
 * <p>실행 로그(node_runs)·채팅 메시지와 분리한 이유: 실행 로그는 워크플로우 삭제 시 함께 지워지고
 * 노드당 한 행이라 재시도 회차 사용량이 빠진다. {@code workflowId}·{@code executionId}는 참조용으로만
 * 두고 FK를 걸지 않는다 — 워크플로우를 지워도 사용량은 남아야 한다.
 */
@Entity
@Table(name = "ai_token_usage",
    indexes = @Index(name = "idx_ai_token_usage_user_created", columnList = "user_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AiTokenUsage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProvider provider;

    /** 실제 호출된 모델의 bare id(provider 접두사 없음, fallback 반영). */
    @Column(nullable = false, length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_source", nullable = false, length = 20)
    private KeySource keySource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UsageSource source;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "execution_id")
    private UUID executionId;

    /** 모델이 usage를 보고하지 않으면 null — 호출 수만 집계된다. */
    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    public enum KeySource { BYOK, PLATFORM }

    public enum UsageSource { WORKFLOW, CHAT }
}
