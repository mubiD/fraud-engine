CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE transactions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   VARCHAR(64)     NOT NULL,
    merchant_id   VARCHAR(64)     NOT NULL,
    amount        NUMERIC(19, 4)  NOT NULL,
    currency      VARCHAR(3)      NOT NULL,
    category      VARCHAR(64),
    location      VARCHAR(128),
    latitude      DECIMAL(9, 6),
    longitude     DECIMAL(9, 6),
    timestamp     TIMESTAMPTZ     NOT NULL,
    status        VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TABLE fraud_assessments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID            NOT NULL REFERENCES transactions(id),
    is_fraudulent    BOOLEAN         NOT NULL,
    risk_score       INTEGER         NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    assessed_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TABLE rule_violations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID        NOT NULL REFERENCES fraud_assessments(id),
    rule_name       VARCHAR(64) NOT NULL,
    rule_version    VARCHAR(16) NOT NULL,
    description     TEXT        NOT NULL,
    severity        VARCHAR(16) NOT NULL
);

CREATE TABLE blacklisted_merchants (
    merchant_id   VARCHAR(64) PRIMARY KEY,
    reason        TEXT,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_transactions_customer_timestamp
    ON transactions(customer_id, timestamp DESC);

CREATE INDEX idx_transactions_duplicate_detection
    ON transactions(merchant_id, amount, customer_id, timestamp DESC);

CREATE INDEX idx_assessments_transaction_id
    ON fraud_assessments(transaction_id);

CREATE INDEX idx_assessments_fraudulent
    ON fraud_assessments(is_fraudulent)
    WHERE is_fraudulent = true;

CREATE INDEX idx_assessments_assessed_at
    ON fraud_assessments(assessed_at DESC)
    WHERE is_fraudulent = true;

CREATE INDEX idx_rule_violations_assessment_id
    ON rule_violations(assessment_id);

CREATE INDEX idx_rule_violations_rule_name
    ON rule_violations(rule_name);
