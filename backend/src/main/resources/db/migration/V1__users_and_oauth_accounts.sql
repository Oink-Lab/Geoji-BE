CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320),
    password_hash VARCHAR(100),
    nickname VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    auth_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_users_email ON users (LOWER(email)) WHERE email IS NOT NULL AND status = 'ACTIVE';
CREATE TABLE oauth_accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_oauth_provider_user UNIQUE (provider, provider_user_id)
);
CREATE INDEX ix_oauth_accounts_user_id ON oauth_accounts(user_id);
