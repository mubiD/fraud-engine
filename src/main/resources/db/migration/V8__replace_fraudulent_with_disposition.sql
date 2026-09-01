ALTER TABLE fraud_assessments ADD COLUMN disposition VARCHAR(32);

UPDATE fraud_assessments
    SET disposition = CASE WHEN is_fraudulent THEN 'FLAGGED' ELSE 'CLEARED' END;

ALTER TABLE fraud_assessments ALTER COLUMN disposition SET NOT NULL;

DROP INDEX IF EXISTS idx_assessments_fraudulent;
DROP INDEX IF EXISTS idx_assessments_assessed_at;
ALTER TABLE fraud_assessments DROP COLUMN is_fraudulent;

-- Replaces both dropped indexes with one composite index covering all three
-- disposition-filtered, assessed_at-ordered query paths (flagged/passed/pending-review).
CREATE INDEX idx_assessments_disposition_assessed_at
    ON fraud_assessments(disposition, assessed_at DESC);
