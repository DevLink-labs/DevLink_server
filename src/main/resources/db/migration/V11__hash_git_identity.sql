-- 기존 Git 이메일을 정규화한 SHA-256 해시로 변환하고 원문 컬럼 제거
ALTER TABLE user_git_settings
    RENAME COLUMN git_email TO git_email_hash;

-- Java trim과 동일한 앞뒤 ASCII 공백 제거 후 해시 변환
UPDATE user_git_settings
SET git_email_hash = encode(sha256(convert_to(lower(btrim(git_email_hash,
    (SELECT string_agg(chr(code), '') FROM generate_series(1, 32) AS whitespace(code)))), 'UTF8')), 'hex');

ALTER TABLE user_git_settings
    ALTER COLUMN git_email_hash TYPE VARCHAR(64),
    ADD CONSTRAINT ck_user_git_settings_email_hash
        CHECK (git_email_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE user_git_settings
    RENAME CONSTRAINT uk_user_git_settings_email TO uk_user_git_settings_email_hash;
