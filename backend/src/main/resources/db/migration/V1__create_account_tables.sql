-- 계정, 로그인 수단, 갱신 토큰. 12_Account_spec.md의 "데이터".

CREATE TABLE account (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 계정과 로그인 수단을 나눈다. 네이버 로그인을 추가할 때 행만 더한다.
CREATE TABLE social_login (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id       BIGINT      NOT NULL REFERENCES account (id),
    provider         VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_social_login_provider_user UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_social_login_account ON social_login (account_id);

-- 갱신 토큰은 원문이 아니라 SHA-256 해시(hex)만 저장한다.
-- used_at이 null이면 아직 쓰지 않은 토큰이다. 쓴 행은 도난 탐지를 위해 만료까지 남긴다.
CREATE TABLE refresh_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES account (id),
    family_id  UUID        NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token (expires_at);
