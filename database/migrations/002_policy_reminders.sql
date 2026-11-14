-- 기존 DB에 1회 적용하세요. 신규 DB는 database/schema.sql을 사용합니다.
ALTER TABLE policies ADD COLUMN apply_start DATE;

CREATE TABLE policy_reminders (
    user_id BIGINT PRIMARY KEY REFERENCES users (id),
    store_id BIGINT REFERENCES stores (id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_sent_date DATE
);
CREATE INDEX idx_policy_reminders_enabled ON policy_reminders (enabled, user_id);
