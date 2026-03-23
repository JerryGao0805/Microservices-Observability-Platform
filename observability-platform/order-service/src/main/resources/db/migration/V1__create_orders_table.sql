CREATE TABLE orders (
    id         UUID PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL,
    amount     NUMERIC(19,2) NOT NULL,
    currency   VARCHAR(3) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    risk_score INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
