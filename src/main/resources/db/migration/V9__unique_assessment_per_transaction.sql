-- Defense-in-depth for TransactionConsumer's Kafka redelivery idempotency guard
-- (findByTransactionId check before evaluate+save+publish): a plain index already
-- existed on transaction_id, but nothing stopped a second fraud_assessments row for
-- the same transaction at the database level. fraud_assessments is not partitioned
-- (unlike transactions), so a straightforward single-column UNIQUE constraint applies.
ALTER TABLE fraud_assessments
    ADD CONSTRAINT uq_assessments_transaction_id UNIQUE (transaction_id);

-- The UNIQUE constraint above creates its own backing index, making the plain
-- lookup index from V4 redundant.
DROP INDEX IF EXISTS idx_assessments_transaction_id;
