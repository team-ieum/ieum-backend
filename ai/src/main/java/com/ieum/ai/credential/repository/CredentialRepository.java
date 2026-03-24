package com.ieum.ai.credential.repository;

import com.ieum.ai.credential.domain.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByIdAndUserId(UUID id, UUID userId);
}
