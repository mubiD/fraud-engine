-- V4: Convert transactions to daily range-partitioned table.
--
-- PostgreSQL 16 requires all unique/PK constraints on a partitioned table to include
-- the partition key (timestamp). The FK from fraud_assessments is therefore dropped
-- and replaced with a composite reference (transaction_id, transaction_timestamp).
-- Application-level integrity is guaranteed: assessments are created inside the same
-- Kafka transaction as the transaction row.

-- Step 1: Add transaction_timestamp to fraud_assessments and backfill
ALTER TABLE fraud_assessments
    ADD COLUMN transaction_timestamp TIMESTAMPTZ;

UPDATE fraud_assessments fa
   SET transaction_timestamp = t.timestamp
  FROM transactions t
 WHERE fa.transaction_id = t.id;

ALTER TABLE fraud_assessments
    ALTER COLUMN transaction_timestamp SET NOT NULL;

-- Step 2: Drop FK and indexes that reference the old single-column PK
ALTER TABLE fraud_assessments
    DROP CONSTRAINT IF EXISTS fraud_assessments_transaction_id_fkey;

DROP INDEX IF EXISTS idx_transactions_customer_timestamp;
DROP INDEX IF EXISTS idx_transactions_duplicate_detection;
DROP INDEX IF EXISTS idx_assessments_transaction_id;

-- Step 3: Rename old table and create new partitioned table
ALTER TABLE transactions RENAME TO transactions_old;

CREATE TABLE transactions (
    id               UUID             NOT NULL,
    customer_id      VARCHAR(64)      NOT NULL,
    merchant_id      VARCHAR(64)      NOT NULL,
    amount           NUMERIC(19, 4)   NOT NULL,
    currency         VARCHAR(3)       NOT NULL,
    category         VARCHAR(64),
    location         VARCHAR(128),
    latitude         DOUBLE PRECISION,
    longitude        DOUBLE PRECISION,
    timestamp        TIMESTAMPTZ      NOT NULL,
    transaction_type VARCHAR(32)      NOT NULL DEFAULT 'CARD_NOT_PRESENT',
    status           VARCHAR(32)      NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (id, timestamp)
) PARTITION BY RANGE (timestamp);

-- Step 4: Default partition catches historical rows outside the named daily range
CREATE TABLE transactions_default PARTITION OF transactions DEFAULT;

-- Step 5: Pre-create daily partitions for today + 7 days ahead
--         The PartitionMaintenanceJob keeps the rolling window current.
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

-- Step 6: Copy all data (historical rows land in transactions_default)
INSERT INTO transactions (id, customer_id, merchant_id, amount, currency, category,
                          location, latitude, longitude, timestamp,
                          transaction_type, status, created_at)
SELECT id, customer_id, merchant_id, amount, currency, category,
       location, latitude, longitude, timestamp,
       transaction_type, status, created_at
  FROM transactions_old;

-- Step 7: Recreate indexes on the partitioned table
--         PostgreSQL propagates indexes to all existing and future partitions.
CREATE INDEX idx_transactions_customer_timestamp
    ON transactions (customer_id, timestamp DESC);

CREATE INDEX idx_transactions_duplicate_detection
    ON transactions (merchant_id, amount, customer_id, timestamp DESC);

-- Step 8: Drop old table
DROP TABLE transactions_old;

-- Step 9: Restore composite FK from fraud_assessments
CREATE INDEX idx_assessments_transaction_id
    ON fraud_assessments (transaction_id);

ALTER TABLE fraud_assessments
    ADD CONSTRAINT fk_assessments_transaction
    FOREIGN KEY (transaction_id, transaction_timestamp)
    REFERENCES transactions (id, timestamp);
