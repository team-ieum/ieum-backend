package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByUserId(UUID userId, Pageable pageable);

    List<Workflow> findByUserIdAndIsActive(UUID userId, boolean isActive, Pageable pageable);

    Optional<Workflow> findByIdAndUserId(UUID id, UUID userId);
}
