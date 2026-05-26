ALTER TABLE connected_accounts
    ADD COLUMN token_expires_at         TIMESTAMP,
    ADD COLUMN refresh_token_expires_at TIMESTAMP;
