package com.ieum.ai.prompt.repository;

import com.ieum.ai.prompt.domain.PromptTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    Optional<PromptTemplate> findByIdAndUserId(UUID id, UUID userId);
}
