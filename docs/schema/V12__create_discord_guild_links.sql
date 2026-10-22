-- Discord 서버 링크 (spec 2026-10-21-discord-bot-design §3). 스키마는 ddl-auto: update가 엔티티(DiscordGuildLink)로
-- 만든다 — 이 파일은 같은 DDL의 기록이다. 배포 뒤 dev에서 `\d discord_guild_links`로 UNIQUE·인덱스를 확인하고,
-- 테이블이 없을 때만 이 파일을 손으로 적용한다. 테이블은 있는데 제약·인덱스만 빠졌으면 아래 주석 2줄을 적용한다.
CREATE TABLE discord_guild_links (
    id               UUID          PRIMARY KEY,
    user_id          UUID          NOT NULL,   -- users FK 없음(slack_user_links와 같음 — 탈퇴는 disconnectAll이 지운다)
    guild_id         VARCHAR(32)   NOT NULL,   -- snowflake 문자열
    guild_name       VARCHAR(100),             -- 연결 시점 이름. 드롭다운 대체 표시용
    discord_user_id  VARCHAR(32),              -- 설치한 Discord 사용자(identify)
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    CONSTRAINT uk_discord_guild_links_user_guild UNIQUE (user_id, guild_id)
);
CREATE INDEX idx_discord_guild_links_guild ON discord_guild_links (guild_id);

-- 제약·인덱스만 빠졌을 때:
-- ALTER TABLE discord_guild_links ADD CONSTRAINT uk_discord_guild_links_user_guild UNIQUE (user_id, guild_id);
-- CREATE INDEX idx_discord_guild_links_guild ON discord_guild_links (guild_id);
