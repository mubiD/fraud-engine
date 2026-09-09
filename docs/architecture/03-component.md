# Component Diagram — Rule Engine Core

C4 Model, Level 3 (Component). Zooms into "Rule Engine Core" from `02-container.md`: the
fraud-evaluation flow shared by the Kafka consumer (production) and the standalone HTTP stub
(dev/demo) — `RuleEngine.evaluate(Transaction)` is the single entry point either caller invokes.

```mermaid
flowchart TB
  subgraph boundary["Rule Engine Core"]
    direction TB
    ruleEngine["RuleEngine<br/>@Component"]
    fraudRuleIface{{"FraudRule<br/>interface"}}
    rules["12 FraudRule impls<br/>@Component beans"]
    contextBuilder["EvaluationContextBuilder<br/>@Component"]
    context["EvaluationContext<br/>value object"]
    referenceCache["ReferenceDataCache<br/>Caffeine @Cacheable"]
    scoringProps["ScoringProperties<br/>@ConfigurationProperties"]
    ruleResult["RuleResult<br/>value object"]
    assessment[("FraudAssessment<br/>JPA entity")]
  end

  transactionRepo["TransactionRepository<br/>Spring Data JPA"]
  streamsStore["Kafka Streams state store<br/>Interactive Query"]

  ruleEngine -->|"build(transaction)"| contextBuilder
  contextBuilder -->|"merchant location"| referenceCache
  contextBuilder -->|"recent history, daily spend, baseline"| streamsStore
  streamsStore -.->|"fallback: StoreUnavailableException"| transactionRepo
  contextBuilder --> context
  ruleEngine -->|"List&lt;FraudRule&gt;, injected"| fraudRuleIface
  fraudRuleIface -.implemented by.-> rules
  rules -->|reads| context
  rules --> ruleResult
  ruleEngine -->|"collect violations"| ruleResult
  ruleEngine -->|"look up likelihood ratio"| scoringProps
  ruleEngine --> assessment

  classDef component fill:#1168bd,color:#fff,stroke:#0b4884
  classDef iface fill:#85bbf0,color:#000,stroke:#5d8fc0
  classDef value fill:#c9c9c9,color:#000,stroke:#8f8f8f
  classDef db fill:#438dd5,color:#fff,stroke:#2d76bd
  classDef ext fill:#999999,color:#fff,stroke:#6b6b6b

  class ruleEngine,contextBuilder,referenceCache,scoringProps component
  class fraudRuleIface,rules iface
  class context,ruleResult value
  class assessment db
  class transactionRepo,streamsStore ext
```

- No short-circuiting — every enabled rule runs regardless of earlier violations; priority only controls order.
- Scoring is log-odds (naive-Bayes), not point-summing — each violation's likelihood ratio (keyed by `RULE_NAME:SEVERITY`) combines additively in log-space, converted to a probability via sigmoid.
- Two thresholds, not one: `fraudProbabilityThreshold` → FLAGGED, `reviewProbabilityThreshold` → PENDING_REVIEW.
- `EvaluationContext` is built once per transaction and shared read-only across all 12 rules — avoids per-rule DB round-trips.
- Adding a rule needs no `RuleEngine` change — `List<FraudRule>` injection auto-collects every `@Component` implementation.
- A missing likelihood-ratio entry falls back to a per-severity default and logs a warning, not a hard failure.

## Assumptions / things to verify

- **The 12 rule implementations are collapsed into one box** — identical shape, no calls between
  them. Full list is in `RuleEngine.getRules()`.
- **`TransactionRepository`/the Kafka Streams state store are shown reaching in from the container
  level** — the state store is the primary path (recent history, daily spend, and the
  `CustomerAmountAnomalyRule` baseline all come from it), Postgres is a fallback only, not a second
  parallel read on every evaluation.
- **`FraudAssessment` is the flow's terminal output** — the actual `save()` and Kafka publish happen
  one level up, in `02-container.md`.
