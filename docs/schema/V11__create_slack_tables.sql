-- Slack 봇 설치·사용자 링크 (spec 2026-10-20-slack-oauth-design §3). 스키마는 ddl-auto: update가 엔티티
-- (SlackInstallation·SlackUserLink)로 만든다 — 이 파일은 같은 DDL의 기록이다. 배포 뒤 dev에서
-- `\d slack_installations`·`\d slack_user_links`로 UNIQUE·FK를 확인하고, 테이블이 없을 때만 이 파일을 손으로 적용한다.
-- 테이블은 있는데 제약만 빠졌으면 아래 ALTER 3줄(주석)을 적용한다.
CREATE TABLE slack_installations (
    id               UUID          PRIMARY KEY,
    team_id          VARCHAR(32)   NOT NULL,
    team_name        VARCHAR(255),
    bot_user_id      VARCHAR(32),
    app_id           VARCHAR(32),
    bot_token        TEXT          NOT NULL,  -- AesEncryptor
    refresh_token    TEXT,                    -- AesEncryptor, rotation 꺼져 있으면 NULL
    token_expires_at TIMESTAMP,               -- rotation 꺼져 있으면 NULL
    scopes           VARCHAR(1000),
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    CONSTRAINT uk_slack_installations_team_id UNIQUE (team_id)
);

CREATE TABLE slack_user_links (
    id              UUID          PRIMARY KEY,
    user_id         UUID          NOT NULL,   -- D1. 여러 개로 갈 때 (user_id, installation_id) 유일로 바꾼다
    installation_id UUID          NOT NULL REFERENCES slack_installations(id),
    slack_user_id   VARCHAR(32),              -- 설치한 Slack 사용자(authed_user.id)
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT uk_slack_user_links_user_id UNIQUE (user_id)
);

-- 제약만 빠졌을 때:
-- ALTER TABLE slack_installations ADD CONSTRAINT uk_slack_installations_team_id UNIQUE (team_id);
-- ALTER TABLE slack_user_links ADD CONSTRAINT uk_slack_user_links_user_id UNIQUE (user_id);
-- ALTER TABLE slack_user_links ADD CONSTRAINT fk_slack_user_links_installation FOREIGN KEY (installation_id) REFERENCES slack_installations(id);
