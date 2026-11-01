package com.ieum.api.prompt.repository;

import com.ieum.api.prompt.domain.PromptTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    Optional<PromptTemplate> findByIdAndUserId(UUID id, UUID userId);

    /** 회원 탈퇴 시 호출자 트랜잭션 안에서 부른다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PromptTemplate e WHERE e.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
