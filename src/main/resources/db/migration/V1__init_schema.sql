-- Consolidated initial schema. This service has not gone live yet, so there is no
-- deployed migration history to preserve — the schema is expressed as a single V1
-- reflecting its current, final shape, rather than the incremental V1-V9 chain it
-- was actually built through during development.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- transactions is range-partitioned by timestamp (daily). PostgreSQL requires the
-- partition key in every unique constraint on a partitioned table, so the primary
-- key is composite (id, timestamp) rather than id alone.
CREATE TABLE transactions (
    id                  UUID             NOT NULL,
    customer_id         VARCHAR(64)      NOT NULL,
    merchant_id         VARCHAR(64)      NOT NULL,
    amount              NUMERIC(19, 4)   NOT NULL,
    currency            VARCHAR(3)       NOT NULL,
    category            VARCHAR(64),
    location            VARCHAR(128),
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    timestamp           TIMESTAMPTZ      NOT NULL,
    transaction_type    VARCHAR(32)      NOT NULL DEFAULT 'CARD_NOT_PRESENT',
    device_fingerprint  VARCHAR(128),
    status              VARCHAR(32)      NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (id, timestamp)
) PARTITION BY RANGE (timestamp);

-- Catches any row outside the named daily partitions below (e.g. backfilled
-- historical data). PartitionMaintenanceJob pre-creates named partitions nightly
-- for today + 2 days and expires ones older than 90 days; this default partition
-- is a safety net, not the steady-state destination for new transactions.
CREATE TABLE transactions_default PARTITION OF transactions DEFAULT;

-- Pre-create daily partitions for today + 7 days ahead so the app is immediately
-- usable on a fresh database, without waiting for PartitionMaintenanceJob's first
-- nightly run.
DO $$
DECLARE
    cur_date DATE := CURRENT_DATE;
    end_date DATE := CURRENT_DATE + INTERVAL '8 days';
    pname    TEXT;
BEGIN
    WHILE cur_date < end_date LOOP
        pname := 'transactions_' || TO_CHAR(cur_date, 'YYYYMMDD');
        EXECUTE FORMAT(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF transactions '
            'FOR VALUES FROM (%L::TIMESTAMPTZ) TO (%L::TIMESTAMPTZ)',
            pname,
            cur_date::TEXT,
            (cur_date + INTERVAL '1 day')::TEXT
        );
        cur_date := cur_date + INTERVAL '1 day';
    END LOOP;
END $$;

CREATE TABLE fraud_assessments (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id         UUID         NOT NULL,
    transaction_timestamp  TIMESTAMPTZ  NOT NULL,
    risk_score             INTEGER      NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    disposition            VARCHAR(32)  NOT NULL,
    outcome                VARCHAR(32)  NOT NULL DEFAULT 'UNRESOLVED',
    assessed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_assessments_transaction_id UNIQUE (transaction_id),
    CONSTRAINT fk_assessments_transaction FOREIGN KEY (transaction_id, transaction_timestamp)
        REFERENCES transactions (id, timestamp)
);

CREATE TABLE rule_violations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID        NOT NULL REFERENCES fraud_assessments(id),
    rule_name       VARCHAR(64) NOT NULL,
    rule_version    VARCHAR(16) NOT NULL,
    description     TEXT        NOT NULL,
    severity        VARCHAR(16) NOT NULL
);

CREATE TABLE merchant_locations (
    merchant_id VARCHAR(64) PRIMARY KEY,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    city        VARCHAR(128),
    country     VARCHAR(64)
);

-- Indexes
CREATE INDEX idx_transactions_customer_timestamp
    ON transactions(customer_id, timestamp DESC);

CREATE INDEX idx_transactions_duplicate_detection
    ON transactions(merchant_id, amount, customer_id, timestamp DESC);

CREATE INDEX idx_transactions_customer_device
    ON transactions(customer_id, device_fingerprint)
    WHERE device_fingerprint IS NOT NULL;

-- One composite index covers all three disposition-filtered, assessed_at-ordered
-- query paths (flagged/pending-review/passed).
CREATE INDEX idx_assessments_disposition_assessed_at
    ON fraud_assessments(disposition, assessed_at DESC);

CREATE INDEX idx_assessments_outcome
    ON fraud_assessments(outcome)
    WHERE outcome <> 'UNRESOLVED';

CREATE INDEX idx_rule_violations_assessment_id
    ON rule_violations(assessment_id);

CREATE INDEX idx_rule_violations_rule_name
    ON rule_violations(rule_name);

-- Seed known physical merchants used in dev/test (GEOGRAPHIC_ANOMALY fallback, §5.5)
INSERT INTO merchant_locations (merchant_id, latitude, longitude, city, country) VALUES
    ('MERCH-WOOLWORTHS-ZA',   -33.9249,  18.4241, 'Cape Town',     'ZA'),
    ('MERCH-CHECKERS-ZA',     -26.2041,  28.0473, 'Johannesburg',  'ZA'),
    ('MERCH-PICK-N-PAY-ZA',   -29.8587,  31.0218, 'Durban',        'ZA'),
    ('MERCH-SHOPRITE-ZA',     -25.7479,  28.2293, 'Pretoria',      'ZA'),
    ('MERCH-CLICKS-ZA',       -26.1070,  28.0567, 'Sandton',       'ZA'),
    ('MERCH-DISCHEM-ZA',      -33.8688,  18.6318, 'Bellville',     'ZA'),
    ('MERCHANT_FRAUD_001',    -26.2041,  28.0473, 'Johannesburg',  'ZA'),
    ('MERCHANT_FRAUD_002',    -33.9249,  18.4241, 'Cape Town',     'ZA'),
    ('MERCHANT_FRAUD_003',    -29.8587,  31.0218, 'Durban',        'ZA');
