package com.ieum.auth.repository;

import com.ieum.auth.domain.DiscordGuildLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscordGuildLinkRepository extends JpaRepository<DiscordGuildLink, UUID> {

    /** 연결 목록·드롭다운·실행 헤더 — 연결한 순서 */
    List<DiscordGuildLink> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<DiscordGuildLink> findByUserIdAndGuildId(UUID userId, String guildId);

    boolean existsByUserIdAndGuildId(UUID userId, String guildId);

    /** 해제 뒤 "그 서버 마지막 IEUM 링크였나" — 커밋 뒤 새 트랜잭션에서만 부른다(spec §6) */
    long countByGuildId(String guildId);
}
