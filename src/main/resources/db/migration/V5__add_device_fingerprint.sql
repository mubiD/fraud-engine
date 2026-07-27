ALTER TABLE transactions ADD COLUMN device_fingerprint VARCHAR(128);

CREATE INDEX idx_transactions_customer_device
    ON transactions(customer_id, device_fingerprint)
    WHERE device_fingerprint IS NOT NULL;
