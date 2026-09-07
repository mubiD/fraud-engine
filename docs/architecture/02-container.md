# Container Diagram — Fraud Rule Engine

C4 Model, Level 2 (Container). Ships as a single Spring Boot JAR / Docker image — there's no
microservice split. The boxes below are the major internal building blocks, not independently
deployable units. See "Assumptions".

```mermaid
flowchart TB
  analyst("👤 Fraud Analyst / Engineer")

  subgraph boundary["Fraud Rule Engine"]
    direction TB
    queryApi["Query API<br/>Spring MVC, 7 controllers"]
    queryServices["Query Services<br/>Spring @Service"]
    consumer["Kafka Consumer<br/>@KafkaListener + @RetryableTopic"]
    ruleEngineCore["Rule Engine Core<br/>Strategy pattern"]
    cache["Reference Data Cache<br/>Caffeine"]
    producer["Kafka Producer<br/>KafkaTemplate"]
    partitionJob["Partition Maintenance Job<br/>@Scheduled, nightly"]
    db[("PostgreSQL<br/>Flyway-managed")]
  end

  kafkaExt{{"Apache Kafka Cluster"}}
  idpExt(["Identity Provider"])
  schemaRegistryExt(["Confluent Schema Registry"])

  kafkaExt -->|"transactions.raw, 6 partitions<br/>concurrency=6"| consumer
  consumer -->|evaluate| ruleEngineCore
  ruleEngineCore -->|"merchant location"| cache
  ruleEngineCore -->|"history, spend, baseline"| db
  consumer -->|"persist transaction + assessment"| db
  consumer --> producer
  producer -->|"flagged / pending-review / passed"| kafkaExt
  producer -->|"register / resolve schema"| schemaRegistryExt
  queryApi --> queryServices
  queryServices -->|read| db
  analyst -->|"HTTPS + JWT"| queryApi
  queryApi -->|validate JWT| idpExt
  partitionJob -->|nightly DDL| db

  classDef person fill:#08427b,color:#fff,stroke:#052e56
  classDef container fill:#1168bd,color:#fff,stroke:#0b4884
  classDef external fill:#999999,color:#fff,stroke:#6b6b6b
  classDef db fill:#438dd5,color:#fff,stroke:#2d76bd

  class analyst person
  class queryApi,queryServices,consumer,ruleEngineCore,cache,producer,partitionJob container
  class kafkaExt,idpExt,schemaRegistryExt external
  class db db
```

Vault isn't shown here — it's a one-time startup config fetch, not owned by any single container
(see `01-context.md`).

- Exactly-once: DB write + Kafka publish share one `ChainedKafkaTransactionManager` transaction — both commit or both roll back.
- Two idempotency guards, not one: `findByIdOnly` (transaction row) and `findByTransactionId` (assessment) — both must pass to skip a Kafka redelivery.
- `ReferenceDataCache` is its own bean, not methods on `EvaluationContextBuilder` — Spring's `@Cacheable` proxy skips self-invoked calls.
- Rule Engine Core is shared by two ingress paths (Kafka consumer in prod, HTTP stub in local/standalone) — same `RuleEngine.evaluate()` call, not duplicated.
- Kafka Consumer/Producer are `@Profile("!standalone & !local")` — absent entirely outside production.
- Listener concurrency (6) matches `transactions.raw`'s 6 partitions (customer-keyed) — preserves per-customer ordering while parallelising.

## Assumptions / things to verify

- **Logical containers inside one JAR**, not independently deployable services —
  `docker-compose.yml` runs a single `fraud-engine` container per environment.
- **"Query Services" groups three unrelated classes** (`TransactionQueryService`,
  `AssessmentOutcomeService`, `RuleManagementService`) — same structural role, no shared code.
- **Partition Maintenance Job talks to `db` via native DDL**, not JPA — kept in the same box as
  everything else for simplicity.
