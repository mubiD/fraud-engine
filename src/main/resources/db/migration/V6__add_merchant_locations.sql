CREATE TABLE merchant_locations (
    merchant_id VARCHAR(64) PRIMARY KEY,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    city        VARCHAR(128),
    country     VARCHAR(64)
);

-- Seed known physical merchants used in dev/test
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
