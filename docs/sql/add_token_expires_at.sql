-- ============================================================
-- IEUM-BE-29: Add token expiry columns to connected_accounts
-- Run manually BEFORE restarting the application.
-- ============================================================
ALTER TABLE connected_accounts
    ADD COLUMN token_expires_at TIMESTAMP,
    ADD COLUMN refresh_token_expires_at TIMESTAMP;
