package com.ieum.api.webhookcredential.repository;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookCredentialRepository extends JpaRepository<WebhookCredential, UUID> {

    List<WebhookCredential> findByUserId(UUID userId);

    Optional<WebhookCredential> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndDisplayName(UUID userId, String displayName);

    long countByUserId(UUID userId);

    /**
     * 알림 대상으로 지정된 크레덴셜. 사용자당 provider별로 하나만 유지되지만, 과거 데이터나
     * 동시 지정으로 둘 이상 남는 경우까지 드러내려고 List로 받는다 —
     * 지정 시 기존 것을 내리는 데도 같은 메서드를 쓴다. 활성 여부는 호출부가 판단한다.
     */
    List<WebhookCredential> findByUserIdAndProviderAndAlertTargetTrue(UUID userId, WebhookProvider provider);
}
