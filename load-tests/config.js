import http from 'k6/http';
import {
  Writer,
  SchemaRegistry,
  VALUE,
  TOPIC_NAME_STRATEGY,
  SCHEMA_TYPE_PROTOBUF,
} from 'k6/x/kafka';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// /api/v1/** requires a Bearer JWT with a FRAUD_ANALYST/FRAUD_ENGINEER role claim under
// SPRING_PROFILES_ACTIVE=load-test (SecurityConfig, application.yml's resource-server config) —
// even for GETs. OIDC_TOKEN_URL points at the mock IdP's token endpoint (see
// docker-compose.load-test.yml's mock-oidc service); fetch once per test run, not per iteration.
export function fetchAccessToken() {
  const tokenUrl = __ENV.OIDC_TOKEN_URL;
  if (!tokenUrl) return null; // no auth wired up (e.g. running against local/standalone) — fine
  // mock-oauth2-server requires *some* client authentication to be present (any value —
  // it doesn't validate credentials against a real client registry) before it evaluates
  // tokenCallbacks; client_secret_post with an arbitrary secret satisfies that.
  const res = http.post(
    tokenUrl,
    'grant_type=client_credentials&client_id=k6-load-test&client_secret=k6-load-test-secret',
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
  );
  if (res.status !== 200) {
    throw new Error(`Failed to obtain access token from ${tokenUrl}: HTTP ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

export function authHeaders(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

// Matches the app's own Kafka config (application.yml `spring.kafka.bootstrap-servers` /
// `fraud.kafka.schema-registry.url`) — same brokers, same registry, reachable by compose
// service name from inside the `load-test` network.
export const KAFKA_BROKERS = (__ENV.KAFKA_BOOTSTRAP_SERVERS || 'kafka1:29092,kafka2:29093,kafka3:29094').split(',');
export const SCHEMA_REGISTRY_URL = __ENV.SCHEMA_REGISTRY_URL || 'http://schema-registry:8081';
export const RAW_TOPIC = 'transactions.raw';

// Lets `make k6-run SCENARIO=... RATE=<n>` (or a bare `k6 run --env RATE=<n> ...`) override
// how much concurrent load a scenario targets, instead of the derived-from-24M-customers
// defaults each scenario falls back to on its own (see README.md's "Capacity numbers"). Each
// scenario decides for itself what RATE means for its shape (a flat rate for 01/04, the peak
// of a ramp/spike for 02/03) — this just resolves the raw override value once.
export function resolveRate(defaultValue) {
  const v = Number(__ENV.RATE);
  return v > 0 ? v : defaultValue;
}

// Thresholds now apply to the assessment-poll HTTP requests only (GET .../assessment) — the
// submit step is a Kafka produce, not an HTTP request, so it isn't covered by http_req_duration.
// Not http_req_failed: k6 counts any 4xx/5xx response as "failed" by default, but the
// assessment-poll step deliberately expects a mix of 200 (processed in time) and 404 (async
// pipeline hasn't caught up yet) — both are correct outcomes, asserted explicitly via
// check(). `checks` is the metric that actually reflects whether polling behaved correctly.
export const THRESHOLDS = {
  http_req_duration: ['p(95)<1500', 'p(99)<2000'],
  checks:            ['rate>0.95'],
};

// TransactionEvent schema, copied verbatim from src/main/proto/transaction_event.proto.
// This is the real production inbound wire format (Confluent Protobuf + Schema Registry) —
// kept in sync by hand if the source .proto changes, the same way the app's own generated
// Java classes are a build-time copy of this same source of truth.
export const TRANSACTION_EVENT_SCHEMA = `
syntax = "proto3";
package com.fraudengine.proto;

import "google/protobuf/timestamp.proto";

enum TransactionType {
  TRANSACTION_TYPE_UNSPECIFIED = 0;
  CARD_NOT_PRESENT = 1;
  CARD_PRESENT = 2;
  CONTACTLESS = 3;
  ATM = 4;
}

message TransactionEvent {
  string transaction_id = 1;
  string customer_id    = 2;
  string merchant_id    = 3;
  string amount         = 4;
  string currency       = 5;
  string category       = 6;
  TransactionType transaction_type = 7;
  string location       = 8;
  double latitude       = 9;
  double longitude      = 10;
  google.protobuf.Timestamp timestamp = 11;
}
`;

const MESSAGE_NAME = 'com.fraudengine.proto.TransactionEvent';

// TransactionType enum values as their proto wire numbers (TRANSACTION_TYPE_UNSPECIFIED=0 is
// deliberately never produced here — every real transaction has a real type).
export const TRANSACTION_TYPE = {
  CARD_NOT_PRESENT: 1,
  CARD_PRESENT: 2,
  CONTACTLESS: 3,
  ATM: 4,
};

export function newKafkaWriter() {
  return new Writer({ brokers: KAFKA_BROKERS, topic: RAW_TOPIC });
}

export function newSchemaRegistry() {
  return new SchemaRegistry({ url: SCHEMA_REGISTRY_URL });
}

// TopicNameStrategy — matches the app's own consumer: application.yml sets no
// key/value.subject.name.strategy override, so Confluent's default (TopicNameStrategy,
// subject = "<topic>-value") is what TransactionConsumer's KafkaProtobufDeserializer expects.
export function registerTransactionEventSchema(schemaRegistry) {
  const subjectName = schemaRegistry.getSubjectName({
    topic: RAW_TOPIC,
    element: VALUE,
    subjectNameStrategy: TOPIC_NAME_STRATEGY,
    schema: TRANSACTION_EVENT_SCHEMA,
    messageName: MESSAGE_NAME,
  });
  return schemaRegistry.createSchema({
    subject: subjectName,
    schema: TRANSACTION_EVENT_SCHEMA,
    schemaType: SCHEMA_TYPE_PROTOBUF,
    messageName: MESSAGE_NAME,
  });
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// RFC 4122 v4 UUID — matches Transaction.id's Java UUID type. Not cryptographically strong,
// which is fine for load-test data generation.
export function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Programmatically generated customer pool, sized to the scenario's expected transaction
// volume so no single customer accumulates unrealistic velocity over the run — an undersized
// pool caused a real, already-documented problem in this codebase (POST /standalone/stream's
// 10-customer pool triggering runaway VELOCITY/CROSS_MERCHANT_VELOCITY cascades and
// decelerating throughput under load, see PROGRESS_LOG.md's 2026-09-05 session). Sized here so
// each customer averages ~targetAvgPerCustomer transactions across the whole scenario run.
export function customerPool(expectedTotalTransactions, targetAvgPerCustomer = 2.5) {
  const size = Math.max(50, Math.ceil(expectedTotalTransactions / targetAvgPerCustomer));
  const pool = new Array(size);
  for (let i = 0; i < size; i++) {
    pool[i] = `CUST_${String(i).padStart(7, '0')}`;
  }
  return pool;
}

// Merchants are shared infrastructure, not per-customer — a much smaller pool is realistic.
export function merchantPool(size = 500) {
  const pool = new Array(size);
  for (let i = 0; i < size; i++) {
    pool[i] = `MERCH_${String(i).padStart(5, '0')}`;
  }
  return pool;
}

export function kafkaTransactionEvent(customers, merchants, overrides = {}) {
  const now = Date.now();
  return {
    transactionId: uuidv4(),
    customerId: pick(customers),
    merchantId: pick(merchants),
    amount: (Math.random() * 1000 + 10).toFixed(2),
    currency: 'GBP',
    category: 'RETAIL',
    transactionType: TRANSACTION_TYPE.CARD_NOT_PRESENT,
    location: 'London, UK',
    latitude: 51.5074,
    longitude: -0.1278,
    // google.protobuf.Timestamp WKT — xk6-kafka's protobuf data mapping goes through
    // protojson semantics, which represents this well-known type as an RFC3339 string, not
    // {seconds, nanos} (confirmed empirically: {seconds, nanos} fails with
    // "proto: syntax error ... unexpected token {" from serialize()).
    timestamp: new Date(now).toISOString(),
    ...overrides,
  };
}
