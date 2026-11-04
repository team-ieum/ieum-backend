package com.ieum.api.usage.repository;

import com.ieum.api.usage.domain.AiTokenUsage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTokenUsageRepository extends JpaRepository<AiTokenUsage, UUID> {
}
