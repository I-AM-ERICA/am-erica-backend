-- 기존 PostgreSQL DB에 1회 적용합니다. 새 DB는 database/schema.sql만 실행하세요.
BEGIN;
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN naver_id VARCHAR(255);
ALTER TABLE users ADD CONSTRAINT uk_users_naver_id UNIQUE (naver_id);
COMMIT;
