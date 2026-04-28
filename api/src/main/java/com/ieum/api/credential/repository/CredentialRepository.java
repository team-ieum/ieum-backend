package com.ieum.api.credential.repository;

import com.ieum.api.credential.domain.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByIdAndUserId(UUID id, UUID userId);
}
