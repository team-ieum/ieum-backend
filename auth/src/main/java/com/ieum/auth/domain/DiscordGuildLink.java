package com.ieum.auth.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * IEUM 사용자 → Discord 서버 링크(spec §3). 봇 토큰은 앱당 하나(BE env)라 저장할 토큰이 없고 (사용자, 서버) 행만 는다(D2).
 * 그 서버에 봇 설치 흐름을 직접 거친 사용자만 행을 갖는다(D1). guild_name은 연결 시점 이름 — 드롭다운 대체 표시용(§10).
 * guild_id 인덱스는 해제 때 "그 서버 남은 링크 수"용(§6) — ddl-auto는 엔티티에 없는 인덱스를 만들지 않아 여기 선언한다.
 */
@Entity
@Table(name = "discord_guild_links",
    uniqueConstraints = @UniqueConstraint(name = "uk_discord_guild_links_user_guild", columnNames = {"user_id", "guild_id"}),
    indexes = @Index(name = "idx_discord_guild_links_guild", columnList = "guild_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscordGuildLink extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "guild_id", nullable = false, length = 32)
    private String guildId;

    @Column(name = "guild_name", length = 100)
    private String guildName;

    /** 설치한 Discord 사용자(identify). 관리 노드의 설치자 권한 확인 확장용(spec §16-4) */
    @Column(name = "discord_user_id", length = 32)
    private String discordUserId;

    public DiscordGuildLink(UUID userId, String guildId, String guildName, String discordUserId) {
        this.userId = userId;
        this.guildId = guildId;
        this.guildName = guildName;
        this.discordUserId = discordUserId;
    }

    /** 같은 사용자가 같은 서버를 다시 연결했을 때 */
    public void refresh(String guildName, String discordUserId) {
        this.guildName = guildName;
        this.discordUserId = discordUserId;
    }
}
