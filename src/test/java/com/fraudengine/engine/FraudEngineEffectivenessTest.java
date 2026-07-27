package com.fraudengine.engine;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.engine.rules.*;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Effectiveness test suite — validates not just that rules work in isolation,
 * but that the fraud engine as a whole detects real fraud patterns while
 * minimising false positives on legitimate transactions.
 *
 * Outputs detection accuracy metrics, pattern coverage, and gap analysis.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FraudEngineEffectivenessTest {

    // Fixed timestamps to keep tests deterministic
    private static final Instant BUSINESS_HOURS  = Instant.parse("2026-07-15T10:00:00Z");
    private static final Instant OFF_HOURS        = Instant.parse("2026-07-15T03:00:00Z");

    private List<FraudRule> rules;

    @BeforeAll
    void buildRuleEngine() {
        RuleProperties props = new RuleProperties();

        // Use production defaults — no test shortcuts
        props.getAmountThreshold().setThreshold(new BigDecimal("5000.00"));
        props.getVelocity().setMaxTransactions(5);
        props.getVelocity().setWindowMinutes(10);
        props.getDuplicate().setCardPresentWindowSeconds(120);
        props.getDuplicate().setCardNotPresentWindowSeconds(300);
        props.getGeographic().setWindowMinutes(60);
        props.getGeographic().setMaxTravelSpeedKmh(900.0);
        props.getCardCloning().setWindowMinutes(10);
        props.getCardCloning().setMinDifferentMerchants(2);
        props.getTimeOfDay().setOffHoursStartHour(23);
        props.getTimeOfDay().setOffHoursEndHour(5);

        rules = List.of(
                new AmountThresholdRule(props),
                new VelocityRule(props),
                new DuplicateTransactionRule(props),
                new BlacklistedMerchantRule(props),
                new GeographicAnomalyRule(props),
                new CardCloningRule(props),
                new TimeOfDayAnomalyRule(props),
                new HighRiskMerchantCategoryRule(props)
        );
    }

    // =========================================================================
    // 1. CORE FRAUD PATTERN COVERAGE
    //    Each test asserts that the relevant rule fires. These are hard failures.
    // =========================================================================

    @Test @Order(10)
    @DisplayName("Pattern: Velocity — card testing (5 prior txns in 10 min)")
    void pattern_velocity_cardTesting() {
        Instant now = BUSINESS_HOURS;
        List<Transaction> history = buildHistory("CUST_VELOCITY", 5, 1, now);

        Assessment result = evaluate(
                tx("CUST_VELOCITY", "SHOP_A", new BigDecimal("25.00"), "ZAR", now),
                history, Set.of());

        assertThat(result.hasViolation("VELOCITY"))
                .as("VELOCITY rule must fire when customer has 5+ transactions in the window")
                .isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(20)
    @DisplayName("Pattern: Duplicate Charge — same merchant, amount, currency within CNP window")
    void pattern_duplicateCharge() {
        Instant now = BUSINESS_HOURS;
        Transaction prior = txFull("CUST_DUP", "STREAMING_SVC", new BigDecimal("149.99"),
                "ZAR", null, null, TransactionType.CARD_NOT_PRESENT,
                now.minus(2, ChronoUnit.MINUTES));

        Assessment result = evaluate(
                txFull("CUST_DUP", "STREAMING_SVC", new BigDecimal("149.99"),
                        "ZAR", null, null, TransactionType.CARD_NOT_PRESENT, now),
                List.of(prior), Set.of());

        assertThat(result.hasViolation("DUPLICATE_TRANSACTION"))
                .as("DUPLICATE_TRANSACTION rule must fire for same merchant/amount/currency within CNP window")
                .isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(30)
    @DisplayName("Pattern: High-Value Transaction — amount > 5000 ZAR")
    void pattern_highValueTransaction() {
        Assessment result = evaluate(
                tx("CUST_HV", "ELECTRONICS_STORE", new BigDecimal("7500.00"), "ZAR", BUSINESS_HOURS),
                List.of(), Set.of());

        assertThat(result.hasViolation("AMOUNT_THRESHOLD"))
                .as("AMOUNT_THRESHOLD rule must fire for amounts above 5000 ZAR")
                .isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(40)
    @DisplayName("Pattern: Blacklisted Merchant — known bad actor")
    void pattern_blacklistedMerchant() {
        Assessment result = evaluate(
                tx("CUST_BL", "MERCHANT_FRAUD_001", new BigDecimal("350.00"), "ZAR", BUSINESS_HOURS),
                List.of(), Set.of("MERCHANT_FRAUD_001", "MERCHANT_FRAUD_002", "MERCHANT_FRAUD_003"));

        assertThat(result.hasViolation("BLACKLISTED_MERCHANT"))
                .as("BLACKLISTED_MERCHANT rule must fire for pre-seeded bad merchants")
                .isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(50)
    @DisplayName("Pattern: Impossible Travel — Cape Town to London in 30 min")
    void pattern_impossibleTravel() {
        Instant now = BUSINESS_HOURS;
        Transaction capeTown = txWithCoords("CUST_GEO", "WOOLWORTHS_CPT",
                new BigDecimal("400.00"), "ZAR",
                -33.9249, 18.4241,
                now.minus(30, ChronoUnit.MINUTES));
        Transaction london = txWithCoords("CUST_GEO", "HARRODS_LDN",
                new BigDecimal("1200.00"), "GBP",
                51.5074, -0.1278, now);

        Assessment result = evaluate(london, List.of(capeTown), Set.of());

        assertThat(result.hasViolation("GEOGRAPHIC_ANOMALY"))
                .as("GEOGRAPHIC_ANOMALY rule must fire when travel speed exceeds 900 km/h")
                .isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(60)
    @DisplayName("Pattern: Card Cloning — same amount at 3 different merchants in 5 min")
    void pattern_cardCloning() {
        Instant now = BUSINESS_HOURS;
        Assessment result = evaluate(
                tx("CUST_CLONE", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", now),
                List.of(
                        tx("CUST_CLONE", "AMAZON.COM",  new BigDecimal("99.99"), "ZAR", now.minus(5, ChronoUnit.MINUTES)),
                        tx("CUST_CLONE", "EBAY.COM",    new BigDecimal("99.99"), "ZAR", now.minus(3, ChronoUnit.MINUTES))),
                Set.of());

        assertThat(result.hasViolation("CARD_CLONING"))
                .as("CARD_CLONING rule must fire when the same amount appears at 2+ different merchants in the window")
                .isTrue();
        // CARD_CLONING is MEDIUM (25 pts) — below the 50 pt fraud threshold on its own.
        // This is a documented gap: the rule detects the pattern but a single MEDIUM signal
        // does not warrant automatic block. Combine with another signal to reach threshold.
        System.out.printf(
                "  [GAP NOTE] CARD_CLONING fired (25 pts) — fraud verdict: %s. "
                + "Needs a second MEDIUM signal to cross the 50 pt threshold.%n",
                result.fraudulent);
    }

    @Test @Order(70)
    @DisplayName("Pattern: High-Risk Category — crypto exchange")
    void pattern_highRiskCategory_crypto() {
        Assessment result = evaluate(
                txWithCategory("CUST_CRYPTO", "LUNO_EXCHANGE", new BigDecimal("500.00"),
                        "ZAR", "CRYPTO_EXCHANGE", BUSINESS_HOURS),
                List.of(), Set.of());

        assertThat(result.hasViolation("HIGH_RISK_MERCHANT_CATEGORY"))
                .as("HIGH_RISK_MERCHANT_CATEGORY rule must fire for crypto exchange transactions")
                .isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(80)
    @DisplayName("Pattern: Time-of-Day Anomaly — transaction at 03:00 UTC")
    void pattern_timeOfDayAnomaly() {
        Assessment result = evaluate(
                tx("CUST_NIGHT", "SOME_SHOP", new BigDecimal("200.00"), "ZAR", OFF_HOURS),
                List.of(), Set.of());

        assertThat(result.hasViolation("TIME_OF_DAY_ANOMALY"))
                .as("TIME_OF_DAY_ANOMALY rule must fire for transactions at 03:00 UTC")
                .isTrue();
        System.out.printf(
                "  [GAP NOTE] TIME_OF_DAY_ANOMALY fired (25 pts) — fraud verdict: %s. "
                + "Needs a second MEDIUM signal to cross the 50 pt threshold.%n",
                result.fraudulent);
    }

    // =========================================================================
    // 2. COMBINED-SIGNAL PATTERNS
    //    Two MEDIUM signals together reach the 50 pt threshold.
    // =========================================================================

    @Test @Order(90)
    @DisplayName("Combined: Card cloning + off-hours = fraud (25 + 25 = 50 pts)")
    void combined_cloningAndOffHours_isFraud() {
        Assessment result = evaluate(
                tx("CUST_COMBO", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", OFF_HOURS),
                List.of(
                        tx("CUST_COMBO", "AMAZON.COM", new BigDecimal("99.99"), "ZAR",
                                OFF_HOURS.minus(5, ChronoUnit.MINUTES)),
                        tx("CUST_COMBO", "EBAY.COM",   new BigDecimal("99.99"), "ZAR",
                                OFF_HOURS.minus(3, ChronoUnit.MINUTES))),
                Set.of());

        assertThat(result.hasViolation("CARD_CLONING")).isTrue();
        assertThat(result.hasViolation("TIME_OF_DAY_ANOMALY")).isTrue();
        assertThat(result.riskScore).isGreaterThanOrEqualTo(50);
        assertThat(result.fraudulent).isTrue();
    }

    @Test @Order(100)
    @DisplayName("Combined: Gambling + off-hours = fraud (25 + 25 = 50 pts)")
    void combined_gamblingAndOffHours_isFraud() {
        Assessment result = evaluate(
                txWithCategory("CUST_GAMBLE", "BETWAY_APP", new BigDecimal("300.00"),
                        "ZAR", "GAMBLING", OFF_HOURS),
                List.of(), Set.of());

        assertThat(result.hasViolation("HIGH_RISK_MERCHANT_CATEGORY")).isTrue();
        assertThat(result.hasViolation("TIME_OF_DAY_ANOMALY")).isTrue();
        assertThat(result.fraudulent).isTrue();
    }

    // =========================================================================
    // 3. LEGITIMATE SCENARIO VALIDATION
    //    None of these should produce a fraud verdict. Hard assertions.
    // =========================================================================

    @Test @Order(200)
    @DisplayName("Legitimate: Weekend shopping — 3 varied purchases during business hours")
    void legitimate_weekendShopping() {
        Assessment result = evaluate(
                tx("CUST_LEGIT_1", "WOOLWORTHS", new BigDecimal("350.00"), "ZAR", BUSINESS_HOURS),
                List.of(
                        tx("CUST_LEGIT_1", "PICK_N_PAY", new BigDecimal("280.00"), "ZAR",
                                BUSINESS_HOURS.minus(45, ChronoUnit.MINUTES)),
                        tx("CUST_LEGIT_1", "EDGARS",     new BigDecimal("620.00"), "ZAR",
                                BUSINESS_HOURS.minus(90, ChronoUnit.MINUTES))),
                Set.of());

        assertThat(result.fraudulent)
                .as("Legitimate weekend shopping with varied merchants/amounts must NOT be flagged")
                .isFalse();
    }

    @Test @Order(210)
    @DisplayName("Legitimate: Large purchase just under threshold (4999.99 ZAR)")
    void legitimate_largePurchaseUnderThreshold() {
        Assessment result = evaluate(
                tx("CUST_LEGIT_2", "SAMSUNG_STORE", new BigDecimal("4999.99"), "ZAR", BUSINESS_HOURS),
                List.of(), Set.of());

        assertThat(result.fraudulent)
                .as("Purchase of exactly 4999.99 ZAR must NOT trigger AMOUNT_THRESHOLD")
                .isFalse();
        assertThat(result.hasViolation("AMOUNT_THRESHOLD")).isFalse();
    }

    @Test @Order(220)
    @DisplayName("Legitimate: Monthly subscription — same merchant/amount, 30+ days apart")
    void legitimate_recurringSubscription() {
        Instant now = BUSINESS_HOURS;
        Transaction lastMonth = txFull("CUST_LEGIT_3", "NETFLIX", new BigDecimal("199.00"),
                "ZAR", null, null, TransactionType.CARD_NOT_PRESENT,
                now.minus(31, ChronoUnit.DAYS));

        Assessment result = evaluate(
                txFull("CUST_LEGIT_3", "NETFLIX", new BigDecimal("199.00"),
                        "ZAR", null, null, TransactionType.CARD_NOT_PRESENT, now),
                List.of(lastMonth), Set.of());

        assertThat(result.hasViolation("DUPLICATE_TRANSACTION"))
                .as("Monthly subscription charge (31 days apart) must NOT trigger DUPLICATE_TRANSACTION")
                .isFalse();
        assertThat(result.fraudulent).isFalse();
    }

    @Test @Order(230)
    @DisplayName("Legitimate: Business travel — Johannesburg to Cape Town (1.4h flight, realistic)")
    void legitimate_businessTravel() {
        Instant now = BUSINESS_HOURS;
        Transaction jhb = txWithCoords("CUST_LEGIT_4", "OR_TAMBO_PARKING",
                new BigDecimal("180.00"), "ZAR",
                -26.1367, 28.2411,
                now.minus(3, ChronoUnit.HOURS));
        Transaction cpt = txWithCoords("CUST_LEGIT_4", "CPT_AIRPORT_COFFEE",
                new BigDecimal("75.00"), "ZAR",
                -33.9725, 18.6017, now);

        Assessment result = evaluate(cpt, List.of(jhb), Set.of());

        assertThat(result.hasViolation("GEOGRAPHIC_ANOMALY"))
                .as("JHB→CPT in 3 hours is realistic (1400 km / 3h ≈ 467 km/h) — must NOT trigger GEOGRAPHIC_ANOMALY")
                .isFalse();
        assertThat(result.fraudulent).isFalse();
    }

    @Test @Order(240)
    @DisplayName("Legitimate: 4 purchases in 15 minutes — below velocity threshold")
    void legitimate_fourPurchasesInFifteenMinutes() {
        Instant now = BUSINESS_HOURS;
        List<Transaction> history = buildHistory("CUST_LEGIT_5", 4, 3, now);

        Assessment result = evaluate(
                tx("CUST_LEGIT_5", "SHOP_X", new BigDecimal("120.00"), "ZAR", now),
                history, Set.of());

        assertThat(result.hasViolation("VELOCITY"))
                .as("4 prior transactions (below max of 5) must NOT trigger VELOCITY rule")
                .isFalse();
        assertThat(result.fraudulent).isFalse();
    }

    @Test @Order(250)
    @DisplayName("Legitimate: Different-currency duplicate — USD vs ZAR at same merchant (Gap 7 fix)")
    void legitimate_differentCurrencySameMerchantAmount() {
        Instant now = BUSINESS_HOURS;
        Transaction zarPayment = txFull("CUST_LEGIT_6", "INTERNATIONAL_SHOP",
                new BigDecimal("100.00"), "ZAR", null, null,
                TransactionType.CARD_NOT_PRESENT,
                now.minus(2, ChronoUnit.MINUTES));

        Assessment result = evaluate(
                txFull("CUST_LEGIT_6", "INTERNATIONAL_SHOP",
                        new BigDecimal("100.00"), "USD", null, null,
                        TransactionType.CARD_NOT_PRESENT, now),
                List.of(zarPayment), Set.of());

        assertThat(result.hasViolation("DUPLICATE_TRANSACTION"))
                .as("100 ZAR followed by 100 USD at the same merchant must NOT be a duplicate (Gap 7 fix)")
                .isFalse();
        assertThat(result.fraudulent).isFalse();
    }

    // =========================================================================
    // 4. EFFECTIVENESS METRICS SUMMARY
    //    Aggregates the full scenario set and prints accuracy metrics.
    // =========================================================================

    @Test @Order(999)
    @DisplayName("Effectiveness Metrics — sensitivity, specificity, precision, F1")
    void effectivenessMetrics() {
        // ---- Fraud scenarios (expected: fraudulent = true) ----
        record FraudScenario(String name, Transaction txn, List<Transaction> history, Set<String> blacklist) {}

        Instant now = BUSINESS_HOURS;

        List<FraudScenario> fraudCases = List.of(
                new FraudScenario("High-Value Transaction",
                        tx("M_HV", "SHOP", new BigDecimal("7500.00"), "ZAR", now),
                        List.of(), Set.of()),

                new FraudScenario("Velocity Attack",
                        tx("M_VEL", "SHOP", new BigDecimal("50.00"), "ZAR", now),
                        buildHistory("M_VEL", 5, 1, now), Set.of()),

                new FraudScenario("Duplicate Charge (CNP)",
                        txFull("M_DUP", "MERCH", new BigDecimal("149.99"), "ZAR",
                                null, null, TransactionType.CARD_NOT_PRESENT, now),
                        List.of(txFull("M_DUP", "MERCH", new BigDecimal("149.99"), "ZAR",
                                null, null, TransactionType.CARD_NOT_PRESENT,
                                now.minus(2, ChronoUnit.MINUTES))),
                        Set.of()),

                new FraudScenario("Blacklisted Merchant",
                        tx("M_BL", "MERCHANT_FRAUD_002", new BigDecimal("500.00"), "ZAR", now),
                        List.of(), Set.of("MERCHANT_FRAUD_001", "MERCHANT_FRAUD_002", "MERCHANT_FRAUD_003")),

                new FraudScenario("Impossible Travel",
                        txWithCoords("M_GEO", "MERCH_LON", new BigDecimal("800.00"), "GBP",
                                51.5074, -0.1278, now),
                        List.of(txWithCoords("M_GEO", "MERCH_CPT", new BigDecimal("400.00"), "ZAR",
                                -33.9249, 18.4241, now.minus(30, ChronoUnit.MINUTES))),
                        Set.of()),

                new FraudScenario("Crypto Exchange",
                        txWithCategory("M_CR", "EXCHANGE", new BigDecimal("500.00"), "ZAR",
                                "CRYPTO_EXCHANGE", now),
                        List.of(), Set.of()),

                new FraudScenario("Card Cloning + Off-Hours",
                        tx("M_CL", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", OFF_HOURS),
                        List.of(
                                tx("M_CL", "AMAZON.COM", new BigDecimal("99.99"), "ZAR",
                                        OFF_HOURS.minus(5, ChronoUnit.MINUTES)),
                                tx("M_CL", "EBAY.COM",   new BigDecimal("99.99"), "ZAR",
                                        OFF_HOURS.minus(3, ChronoUnit.MINUTES))),
                        Set.of())
        );

        // ---- Legitimate scenarios (expected: fraudulent = false) ----
        record LegitScenario(String name, Transaction txn, List<Transaction> history, Set<String> blacklist) {}

        List<LegitScenario> legitCases = List.of(
                new LegitScenario("Weekend Shopping",
                        tx("L_WS", "WOOLWORTHS", new BigDecimal("350.00"), "ZAR", now),
                        List.of(
                                tx("L_WS", "PICK_N_PAY", new BigDecimal("280.00"), "ZAR",
                                        now.minus(45, ChronoUnit.MINUTES)),
                                tx("L_WS", "EDGARS",     new BigDecimal("620.00"), "ZAR",
                                        now.minus(90, ChronoUnit.MINUTES))),
                        Set.of()),

                new LegitScenario("Large Purchase Under Threshold",
                        tx("L_LP", "SAMSUNG", new BigDecimal("4999.99"), "ZAR", now),
                        List.of(), Set.of()),

                new LegitScenario("Monthly Subscription",
                        txFull("L_SUB", "NETFLIX", new BigDecimal("199.00"), "ZAR",
                                null, null, TransactionType.CARD_NOT_PRESENT, now),
                        List.of(txFull("L_SUB", "NETFLIX", new BigDecimal("199.00"), "ZAR",
                                null, null, TransactionType.CARD_NOT_PRESENT,
                                now.minus(31, ChronoUnit.DAYS))),
                        Set.of()),

                new LegitScenario("Business Travel (JHB→CPT)",
                        txWithCoords("L_BT", "CPT_SHOP", new BigDecimal("75.00"), "ZAR",
                                -33.9725, 18.6017, now),
                        List.of(txWithCoords("L_BT", "JHB_PARK", new BigDecimal("180.00"), "ZAR",
                                -26.1367, 28.2411, now.minus(3, ChronoUnit.HOURS))),
                        Set.of()),

                new LegitScenario("4 Transactions (Below Velocity Max)",
                        tx("L_VEL", "SHOP_X", new BigDecimal("120.00"), "ZAR", now),
                        buildHistory("L_VEL", 4, 1, now), Set.of())
        );

        // ---- Evaluate and classify ----
        int tp = 0, fn = 0, tn = 0, fp = 0;
        Map<String, Boolean> fraudResults   = new LinkedHashMap<>();
        Map<String, Boolean> legitResults   = new LinkedHashMap<>();

        for (FraudScenario s : fraudCases) {
            boolean detected = evaluate(s.txn(), s.history(), s.blacklist()).fraudulent;
            fraudResults.put(s.name(), detected);
            if (detected) tp++; else fn++;
        }
        for (LegitScenario s : legitCases) {
            boolean flagged = evaluate(s.txn(), s.history(), s.blacklist()).fraudulent;
            legitResults.put(s.name(), flagged);
            if (!flagged) tn++; else fp++;
        }

        // ---- Calculate metrics ----
        double sensitivity = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        double specificity = tn + fp == 0 ? 0 : (double) tn / (tn + fp);
        double precision   = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double fpr         = fp + tn == 0 ? 0 : (double) fp / (fp + tn);
        double fnr         = fn + tp == 0 ? 0 : (double) fn / (fn + tp);
        double f1          = precision + sensitivity == 0 ? 0
                : 2 * (precision * sensitivity) / (precision + sensitivity);

        // ---- Coverage by pattern ----
        Map<String, Boolean> patternCoverage = new LinkedHashMap<>();
        patternCoverage.put("VELOCITY",                    fraudResults.containsValue(true)
                && fraudResults.get("Velocity Attack") != null && fraudResults.get("Velocity Attack"));
        patternCoverage.put("DUPLICATE_TRANSACTION",       fraudResults.get("Duplicate Charge (CNP)") != null
                && fraudResults.get("Duplicate Charge (CNP)"));
        patternCoverage.put("AMOUNT_THRESHOLD",            fraudResults.get("High-Value Transaction") != null
                && fraudResults.get("High-Value Transaction"));
        patternCoverage.put("BLACKLISTED_MERCHANT",        fraudResults.get("Blacklisted Merchant") != null
                && fraudResults.get("Blacklisted Merchant"));
        patternCoverage.put("GEOGRAPHIC_ANOMALY",          fraudResults.get("Impossible Travel") != null
                && fraudResults.get("Impossible Travel"));
        patternCoverage.put("CARD_CLONING",                evaluate(
                tx("CC", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", now),
                List.of(tx("CC", "AMAZON.COM",  new BigDecimal("99.99"), "ZAR", now.minus(5, ChronoUnit.MINUTES)),
                        tx("CC", "EBAY.COM",    new BigDecimal("99.99"), "ZAR", now.minus(3, ChronoUnit.MINUTES))),
                Set.of()).hasViolation("CARD_CLONING"));
        patternCoverage.put("HIGH_RISK_MERCHANT_CATEGORY", fraudResults.get("Crypto Exchange") != null
                && fraudResults.get("Crypto Exchange"));
        patternCoverage.put("TIME_OF_DAY_ANOMALY",         evaluate(
                tx("TOD", "SHOP", new BigDecimal("200.00"), "ZAR", OFF_HOURS),
                List.of(), Set.of()).hasViolation("TIME_OF_DAY_ANOMALY"));

        // ---- Print report ----
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  FRAUD ENGINE EFFECTIVENESS METRICS");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  Confusion matrix:  TP=%d  FP=%d  TN=%d  FN=%d%n", tp, fp, tn, fn);
        System.out.println();
        printMetric("Sensitivity (TPR)", sensitivity, 0.90, "%");
        printMetric("Specificity (TNR)", specificity, 0.95, "%");
        printMetric("Precision",          precision,  0.90, "%");
        printMetric("False Positive Rate",fpr,        0.05, "% (lower is better)");
        printMetric("False Negative Rate",fnr,        0.05, "% (lower is better)");
        printMetric("F1 Score",           f1,         0.85, "");
        System.out.println();
        System.out.println("  FRAUD PATTERN COVERAGE:");
        patternCoverage.forEach((pattern, covered) ->
                System.out.printf("    %-35s %s%n", pattern, covered ? "✓ COVERED" : "✗ GAP DETECTED"));
        System.out.println();
        System.out.println("  KNOWN UNIMPLEMENTED PATTERNS (require infrastructure changes):");
        System.out.println("    DEVICE_FINGERPRINTING                  N/A — needs device tracking data");
        System.out.println("    CUMULATIVE_SPENDING_LIMITS             N/A — needs daily/monthly aggregation");
        System.out.println("    CROSS_CHANNEL_ANOMALY                  N/A — needs enriched channel metadata");
        System.out.println();
        System.out.println("  FRAUD SCENARIO RESULTS:");
        fraudResults.forEach((name, detected) ->
                System.out.printf("    %-40s %s%n", name, detected ? "✓ DETECTED" : "✗ MISSED"));
        System.out.println();
        System.out.println("  LEGITIMATE SCENARIO RESULTS:");
        legitResults.forEach((name, flagged) ->
                System.out.printf("    %-40s %s%n", name, !flagged ? "✓ PASSED" : "✗ FALSE POSITIVE"));
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        // ---- Hard assertions on minimum acceptable thresholds ----
        assertThat(tp).as("All 5 core fraud patterns must be detected (velocity, duplicate, amount, blacklist, geographic)").isGreaterThanOrEqualTo(5);
        assertThat(tn).as("All legitimate scenarios must pass without false positives").isEqualTo(legitCases.size());
        assertThat(sensitivity).as("Sensitivity must be at least 0.70").isGreaterThanOrEqualTo(0.70);
        assertThat(f1).as("F1 score must be at least 0.70").isGreaterThanOrEqualTo(0.70);
        assertThat(patternCoverage.get("CARD_CLONING")).as("CARD_CLONING rule must fire (pattern detected)").isTrue();
        assertThat(patternCoverage.get("TIME_OF_DAY_ANOMALY")).as("TIME_OF_DAY_ANOMALY rule must fire").isTrue();
        assertThat(patternCoverage.get("HIGH_RISK_MERCHANT_CATEGORY")).as("HIGH_RISK_MERCHANT_CATEGORY rule must fire").isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Assessment evaluate(Transaction txn, List<Transaction> history, Set<String> blacklist) {
        EvaluationContext ctx = EvaluationContext.builder()
                .recentCustomerTransactions(history)
                .blacklistedMerchantIds(blacklist)
                .build();

        List<RuleResult> violations = rules.stream()
                .filter(FraudRule::isEnabled)
                .sorted(Comparator.comparingInt(FraudRule::getPriority))
                .map(r -> r.evaluate(txn, ctx))
                .filter(RuleResult::isViolation)
                .collect(Collectors.toList());

        int rawScore = violations.stream().mapToInt(v -> v.getSeverity().getScoreWeight()).sum();
        int riskScore = Math.min(rawScore, 100);
        return new Assessment(riskScore >= 50, riskScore, violations);
    }

    private List<Transaction> buildHistory(String customerId, int count, int intervalMinutes, Instant base) {
        List<Transaction> history = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            history.add(tx(customerId, "SHOP_" + i, new BigDecimal("25.00"), "ZAR",
                    base.minus((long) i * intervalMinutes, ChronoUnit.MINUTES)));
        }
        return history;
    }

    private Transaction tx(String customerId, String merchantId, BigDecimal amount, String currency, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId(customerId).merchantId(merchantId)
                .amount(amount).currency(currency).timestamp(ts).build();
    }

    private Transaction txFull(String customerId, String merchantId, BigDecimal amount, String currency,
                                Double lat, Double lon, TransactionType type, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId(customerId).merchantId(merchantId)
                .amount(amount).currency(currency).latitude(lat).longitude(lon)
                .transactionType(type).timestamp(ts).build();
    }

    private Transaction txWithCoords(String customerId, String merchantId, BigDecimal amount,
                                      String currency, double lat, double lon, Instant ts) {
        return txFull(customerId, merchantId, amount, currency, lat, lon, TransactionType.CARD_PRESENT, ts);
    }

    private Transaction txWithCategory(String customerId, String merchantId, BigDecimal amount,
                                        String currency, String category, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId(customerId).merchantId(merchantId)
                .amount(amount).currency(currency).category(category).timestamp(ts).build();
    }

    private void printMetric(String name, double value, double target, String suffix) {
        boolean isRate = name.contains("Rate");
        boolean passes = isRate ? value <= target : value >= target;
        String indicator = passes ? "✓" : (Math.abs(value - target) < 0.10 ? "⚠" : "✗");
        if (name.equals("F1 Score")) {
            System.out.printf("  %-25s %.2f   %s  (Target: %.2f)%n", name, value, indicator, target);
        } else {
            System.out.printf("  %-25s %5.1f%%  %s  (Target: %s%.0f%%)%n",
                    name, value * 100, indicator, isRate ? "< " : "> ", target * 100);
        }
    }

    private record Assessment(boolean fraudulent, int riskScore, List<RuleResult> violations) {
        boolean hasViolation(String ruleName) {
            return violations.stream().anyMatch(v -> v.getRuleName().equals(ruleName));
        }
    }
}
