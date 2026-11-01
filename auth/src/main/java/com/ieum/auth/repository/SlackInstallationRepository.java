package com.ieum.auth.repository;

import com.ieum.auth.domain.SlackInstallation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlackInstallationRepository extends JpaRepository<SlackInstallation, UUID> {

    /** 콜백 upsert가 같은 팀 설치 행을 잡는다 — 해제·갱신과 직렬화된다. 트랜잭션 안에서만 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SlackInstallation i WHERE i.teamId = :teamId")
    Optional<SlackInstallation> findByTeamIdForUpdate(@Param("teamId") String teamId);

    /** 해제가 설치 행을 잡는다. 트랜잭션 안에서만 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SlackInstallation i WHERE i.id = :id")
    Optional<SlackInstallation> findByIdForUpdate(@Param("id") UUID id);
}
