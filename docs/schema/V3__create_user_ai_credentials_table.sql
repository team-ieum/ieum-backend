CREATE TABLE user_ai_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    credential_type VARCHAR(10) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    encrypted_api_key TEXT,
    key_hint VARCHAR(20),
    is_valid BOOLEAN NOT NULL DEFAULT true,
    last_validated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_ai_credentials_user_id ON user_ai_credentials(user_id);
CREATE INDEX idx_user_ai_credentials_provider ON user_ai_credentials(user_id, provider);
