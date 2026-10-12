package com.ieum.workflowcore.service;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowWebhookToken;
import com.ieum.workflowcore.repository.WorkflowWebhookTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹훅 수신 토큰 발급·조회. 소유자 검사는 호출자 몫이다({@code WorkflowCrudService.getWorkflowByOwner}).
 * 평문은 {@link #issue}의 반환값으로만 나간다 — DB에는 SHA-256 hex만 남는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowWebhookTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkflowWebhookTokenRepository repository;

    public record IssuedToken(String token, LocalDateTime issuedAt) {}

    /** 새 토큰을 발급한다. 이미 있으면 같은 행의 해시를 바꿔 이전 토큰을 즉시 무효화한다. */
    @Transactional
    public IssuedToken issue(Workflow workflow) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(plain);
        LocalDateTime now = LocalDateTime.now();

        repository.findByWorkflowId(workflow.getId()).ifPresentOrElse(
            token -> token.reissue(hash, now),
            () -> repository.save(WorkflowWebhookToken.create(workflow.getId(), hash, now)));
        return new IssuedToken(plain, now);
    }

    /** 발급 시각. 발급된 적 없으면 empty. */
    public Optional<LocalDateTime> status(UUID workflowId) {
        return repository.findByWorkflowId(workflowId).map(WorkflowWebhookToken::getIssuedAt);
    }

    public Optional<UUID> findWorkflowIdByToken(String plain) {
        if (plain == null || plain.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTokenHash(hash(plain)).map(WorkflowWebhookToken::getWorkflowId);
    }

    static String hash(String plain) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK 구현체가 필수 제공 — 발생하지 않는다
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
