CREATE TABLE remember_login_tokens (
    token_hash VARCHAR(64) NOT NULL,
    user_id    BIGINT      NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_remember_login_tokens PRIMARY KEY (token_hash),
    CONSTRAINT fk_remember_login_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_remember_login_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_remember_login_tokens_expiry ON remember_login_tokens (expires_at);
