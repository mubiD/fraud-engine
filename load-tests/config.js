export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const THRESHOLDS = {
  http_req_duration: ['p(95)<1500', 'p(99)<2000'],
  http_req_failed:   ['rate<0.05'],
};

// Shared transaction data pools
export const CUSTOMERS = [
  'CUST_001', 'CUST_002', 'CUST_003', 'CUST_004', 'CUST_005',
];

export const MERCHANTS = {
  clean:       ['MERCH_CLEAN_1', 'MERCH_CLEAN_2', 'MERCH_CLEAN_3'],
  blacklisted: ['MERCHANT_FRAUD_001', 'MERCHANT_FRAUD_002'],
};

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function transactionPayload(overrides = {}) {
  return JSON.stringify({
    customerId: pick(CUSTOMERS),
    merchantId: pick(MERCHANTS.clean),
    amount:     (Math.random() * 1000 + 10).toFixed(2),
    currency:   'GBP',
    category:   'RETAIL',
    location:   'London, UK',
    latitude:   51.5074,
    longitude:  -0.1278,
    ...overrides,
  });
}
