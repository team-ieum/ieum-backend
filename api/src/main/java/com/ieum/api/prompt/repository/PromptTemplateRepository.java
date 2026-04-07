package com.ieum.api.prompt.repository;

import com.ieum.api.prompt.domain.PromptTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    Optional<PromptTemplate> findByIdAndUserId(UUID id, UUID userId);
}
