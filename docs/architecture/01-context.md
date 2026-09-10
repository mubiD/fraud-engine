# System Context — Fraud Rule Engine

C4 Model, Level 1 (Context). Scope: production topology (`load-test`/`prod`), where Kafka is
the only ingestion path. See "Assumptions" for how `local`/`standalone` differ.

```mermaid
flowchart TB
  analyst("👤 Fraud Analyst / Engineer")
  fraudEngine["Fraud Rule Engine"]

  upstream(["Transaction Source"])
  kafka{{"Apache Kafka Cluster"}}
  postgres[("PostgreSQL")]
  schemaRegistry(["Confluent Schema Registry"])
  idp(["Identity Provider"])
  vault(["HashiCorp Vault"])
  downstream(["Downstream Consumers"])

  upstream -->|"publishes TransactionEvent (Protobuf)"| kafka
  kafka -->|transactions.raw| fraudEngine
  fraudEngine -->|"flagged / pending-review / passed"| kafka
  kafka --> downstream
  fraudEngine -->|JDBC| postgres
  fraudEngine -->|"register / resolve schema"| schemaRegistry
  fraudEngine -->|"fetch secrets at startup"| vault
  analyst -->|"HTTPS + JWT"| fraudEngine
  fraudEngine -->|validate JWT| idp

  classDef person fill:#08427b,color:#fff,stroke:#052e56
  classDef internal fill:#1168bd,color:#fff,stroke:#0b4884
  classDef external fill:#999999,color:#fff,stroke:#6b6b6b
  classDef db fill:#438dd5,color:#fff,stroke:#2d76bd

  class analyst person
  class fraudEngine internal
  class upstream,kafka,schemaRegistry,idp,vault,downstream external
  class postgres db
```

- Kafka is the only ingestion path in `load-test`/`prod`; no HTTP submission endpoint exists there.
- The Analyst's only write action is `PATCH .../outcome`; everything else is read-only.
- Schema Registry and Vault are production-only: `local`/`standalone` skip both.
- The IDP is bypassed entirely in `local`/`standalone`/`test` (`SecurityConfig`'s `noSecurityFilterChain`).

## Assumptions / things to verify

- **Production topology only.** `local`/`standalone` swap in a synchronous HTTP stub
  (`StandaloneTransactionController`) that bypasses Kafka, Schema Registry, and JWT entirely. It's a
  genuinely different diagram, not a subset of this one.
- **"Downstream Consumers" and "Transaction Source" are inferred**, not concrete systems in this
  repo, based on topic names and `DESIGN.md`'s stated intent ("alerting, reporting, model
  training"). Verify against your actual upstream/downstream systems.
- **Vault access is a startup-time concern** (Spring Cloud Vault Config), not per-request. It's shown as
  one relationship, not a hot-path dependency.
