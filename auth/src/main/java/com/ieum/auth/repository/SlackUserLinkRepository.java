package com.ieum.auth.repository;

import com.ieum.auth.domain.SlackUserLink;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackUserLinkRepository extends JpaRepository<SlackUserLink, UUID> {

    Optional<SlackUserLink> findByUserId(UUID userId);

    long countByInstallation_Id(UUID installationId);
}
