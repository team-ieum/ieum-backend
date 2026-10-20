package com.ieum.api.credential.repository;

import com.ieum.api.credential.domain.Credential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByIdAndUserId(UUID id, UUID userId);

    /** 회원 탈퇴 시 호출자 트랜잭션 안에서 부른다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Credential e WHERE e.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
