ALTER TABLE fraud_assessments
    ADD COLUMN outcome VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED';

CREATE INDEX idx_assessments_outcome
    ON fraud_assessments(outcome)
    WHERE outcome <> 'UNRESOLVED';
