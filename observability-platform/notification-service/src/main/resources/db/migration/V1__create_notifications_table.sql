CREATE TABLE notifications (
    id       UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    user_id  VARCHAR(255) NOT NULL,
    message  VARCHAR(1000) NOT NULL,
    channel  VARCHAR(20) NOT NULL,
    sent_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
