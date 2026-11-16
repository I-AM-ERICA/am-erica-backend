-- 001/002까지 적용된 기존 DB에 1회 적용합니다.
CREATE TABLE account_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users (id),
    store_id BIGINT REFERENCES stores (id),
    owner_name VARCHAR(50),
    policy_gender VARCHAR(4) NOT NULL DEFAULT 'boy' CHECK (policy_gender IN ('boy', 'girl')),
    marketing_gender VARCHAR(4) NOT NULL DEFAULT 'girl' CHECK (marketing_gender IN ('boy', 'girl')),
    customer_gender VARCHAR(4) NOT NULL DEFAULT 'girl' CHECK (customer_gender IN ('boy', 'girl')),
    sales_gender VARCHAR(4) NOT NULL DEFAULT 'boy' CHECK (sales_gender IN ('boy', 'girl'))
);
