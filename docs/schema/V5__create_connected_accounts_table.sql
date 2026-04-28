CREATE TABLE connected_accounts (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users(id),
    provider      VARCHAR(20)  NOT NULL,
    access_token  TEXT         NOT NULL,
    refresh_token TEXT,
    scopes        VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider)
);

CREATE INDEX idx_connected_accounts_user_id ON connected_accounts(user_id);
