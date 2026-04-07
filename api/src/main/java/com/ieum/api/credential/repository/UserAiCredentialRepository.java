package com.ieum.api.credential.repository;

import com.ieum.api.credential.domain.UserAiCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAiCredentialRepository extends JpaRepository<UserAiCredential, UUID> {

    Optional<UserAiCredential> findByIdAndUserId(UUID id, UUID userId);
}
