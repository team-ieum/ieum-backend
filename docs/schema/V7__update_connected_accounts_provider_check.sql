ALTER TABLE connected_accounts
    DROP CONSTRAINT connected_accounts_provider_check,
    ADD CONSTRAINT connected_accounts_provider_check
        CHECK (provider IN ('LOCAL', 'GOOGLE', 'NOTION', 'GITHUB'));
