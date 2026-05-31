package com.ieum.api.webhookcredential.repository;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookCredentialRepository extends JpaRepository<WebhookCredential, UUID> {

    List<WebhookCredential> findByUserId(UUID userId);

    Optional<WebhookCredential> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndDisplayName(UUID userId, String displayName);

    long countByUserId(UUID userId);
}
