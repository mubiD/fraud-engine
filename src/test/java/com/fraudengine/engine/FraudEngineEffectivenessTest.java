package com.fraudengine.engine;

import com.fraudengine.config.RuleProperties;
import com.fraudengine.config.ScoringProperties;
import com.fraudengine.engine.rules.*;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.RuleViolation;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.Disposition;
import com.fraudengine.model.enums.TransactionType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Effectiveness test suite — validates not just that rules work in isolation,
 * but that the fraud engine as a whole detects real fraud patterns while
 * minimising false positives on legitimate transactions.
 *
 * Runs through the real RuleEngine (with a stubbed EvaluationContextBuilder,
 * so no database is needed) rather than reimplementing scoring locally — a
 * second copy of the scoring formula here would silently drift from
 * RuleEngine's actual behaviour the next time either one changed.
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
    private RuleEngine ruleEngine;
    private EvaluationContextBuilder mockContextBuilder;

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
        props.getDeviceFingerprint().setWindowMinutes(60);
        props.getMultiChannel().setWindowMinutes(5);
        props.getCrossMerchantVelocity().setMaxTransactions(10);
        props.getCrossMerchantVelocity().setWindowMinutes(10);
        props.getCumulativeSpending().setHourlyLimit(new BigDecimal("10000.00"));
        props.getCumulativeSpending().setDailyLimit(new BigDecimal("25000.00"));
        props.getCumulativeSpending().setHourlyWindowMinutes(60);
        props.getCustomerAmountAnomaly().setLookbackDays(90);
        props.getCustomerAmountAnomaly().setMinHistoryCount(5);
        props.getCustomerAmountAnomaly().setStddevMultiplier(3.0);

        rules = List.of(
                new AmountThresholdRule(props),
                new VelocityRule(props),
                new DuplicateTransactionRule(props),
                new BlacklistedMerchantRule(props),
                new GeographicAnomalyRule(props),
                new CardCloningRule(props),
                new TimeOfDayAnomalyRule(props),
                new HighRiskMerchantCategoryRule(props),
                new DeviceFingerprintRule(props),
                new MultiChannelAnomalyRule(props),
                new CrossMerchantVelocityRule(props),
                new CumulativeSpendingRule(props),
                new CustomerAmountAnomalyRule(props)
        );

        mockContextBuilder = mock(EvaluationContextBuilder.class);
        ruleEngine = new RuleEngine(rules, mockContextBuilder, new ScoringProperties());
    }

    // =========================================================================
    // 0. META — guards against this suite silently falling behind the rule set
    // =========================================================================

    @Test @Order(0)
    @DisplayName("Meta: every FraudRule implementation is exercised by this suite")
    void ruleListIsExhaustive_allRuleClassesRepresented() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(FraudRule.class));

        Set<String> declaredRuleClasses = scanner.findCandidateComponents("com.fraudengine.engine.rules").stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        Set<String> exercisedRuleClasses = rules.stream()
                .map(r -> r.getClass().getName())
                .collect(Collectors.toSet());

        assertThat(exercisedRuleClasses)
                .as("A FraudRule implementation exists under engine.rules that this effectiveness suite "
                        + "doesn't exercise — add it to buildRuleEngine() so the sensitivity/F1 metrics "
                        + "reflect the full rule set, not a stale subset of it")
                .containsExactlyInAnyOrderElementsOf(declaredRuleClasses);
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

        FraudAssessment result = evaluate(
                tx("CUST_VELOCITY", "SHOP_A", new BigDecimal("25.00"), "ZAR", now),
                history, Set.of());

        assertThat(hasViolation(result, "VELOCITY"))
                .as("VELOCITY rule must fire when customer has 5+ transactions in the window")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
    }

    @Test @Order(20)
    @DisplayName("Pattern: Duplicate Charge — same merchant, amount, currency within CNP window")
    void pattern_duplicateCharge() {
        Instant now = BUSINESS_HOURS;
        Transaction prior = txFull("CUST_DUP", "STREAMING_SVC", new BigDecimal("149.99"),
                "ZAR", null, null, TransactionType.CARD_NOT_PRESENT,
                now.minus(2, ChronoUnit.MINUTES));

        FraudAssessment result = evaluate(
                txFull("CUST_DUP", "STREAMING_SVC", new BigDecimal("149.99"),
                        "ZAR", null, null, TransactionType.CARD_NOT_PRESENT, now),
                List.of(prior), Set.of());

        assertThat(hasViolation(result, "DUPLICATE_TRANSACTION"))
                .as("DUPLICATE_TRANSACTION rule must fire for same merchant/amount/currency within CNP window")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
    }

    @Test @Order(30)
    @DisplayName("Pattern: High-Value Transaction — amount > 5000 ZAR (weak signal alone)")
    void pattern_highValueTransaction() {
        FraudAssessment result = evaluate(
                tx("CUST_HV", "ELECTRONICS_STORE", new BigDecimal("7500.00"), "ZAR", BUSINESS_HOURS),
                List.of(), Set.of());

        assertThat(hasViolation(result, "AMOUNT_THRESHOLD"))
                .as("AMOUNT_THRESHOLD rule must fire for amounts above 5000 ZAR")
                .isTrue();
        // AMOUNT_THRESHOLD is deliberately calibrated as weak evidence on its own —
        // a large legitimate purchase (a flight, an appliance) is common enough that
        // amount alone shouldn't auto-flag it. See ScoringProperties for rationale.
        // This was previously a hard "must be fraudulent alone" assertion; changing
        // that was the point of the log-odds rescoring, not a regression.
        System.out.printf(
                "  [GAP NOTE] AMOUNT_THRESHOLD fired alone — disposition: %s, riskScore: %d. "
                + "Needs a corroborating signal to cross the fraud threshold.%n",
                result.getDisposition(), result.getRiskScore());
    }

    @Test @Order(40)
    @DisplayName("Pattern: Blacklisted Merchant — known bad actor")
    void pattern_blacklistedMerchant() {
        FraudAssessment result = evaluate(
                tx("CUST_BL", "MERCHANT_FRAUD_001", new BigDecimal("350.00"), "ZAR", BUSINESS_HOURS),
                List.of(), Set.of("MERCHANT_FRAUD_001", "MERCHANT_FRAUD_002", "MERCHANT_FRAUD_003"));

        assertThat(hasViolation(result, "BLACKLISTED_MERCHANT"))
                .as("BLACKLISTED_MERCHANT rule must fire for pre-seeded bad merchants")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
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

        FraudAssessment result = evaluate(london, List.of(capeTown), Set.of());

        assertThat(hasViolation(result, "GEOGRAPHIC_ANOMALY"))
                .as("GEOGRAPHIC_ANOMALY rule must fire when travel speed exceeds 900 km/h")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
    }

    @Test @Order(60)
    @DisplayName("Pattern: Card Cloning — same amount at 3 different merchants in 5 min")
    void pattern_cardCloning() {
        Instant now = BUSINESS_HOURS;
        FraudAssessment result = evaluate(
                tx("CUST_CLONE", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", now),
                List.of(
                        tx("CUST_CLONE", "AMAZON.COM",  new BigDecimal("99.99"), "ZAR", now.minus(5, ChronoUnit.MINUTES)),
                        tx("CUST_CLONE", "EBAY.COM",    new BigDecimal("99.99"), "ZAR", now.minus(3, ChronoUnit.MINUTES))),
                Set.of());

        assertThat(hasViolation(result, "CARD_CLONING"))
                .as("CARD_CLONING rule must fire when the same amount appears at 2+ different merchants in the window")
                .isTrue();
        // CARD_CLONING is calibrated as weak evidence alone — below the fraud
        // threshold on its own. Combine with another signal to cross it.
        System.out.printf(
                "  [GAP NOTE] CARD_CLONING fired alone — disposition: %s, riskScore: %d. "
                + "Needs a second corroborating signal to cross the fraud threshold.%n",
                result.getDisposition(), result.getRiskScore());
    }

    @Test @Order(70)
    @DisplayName("Pattern: High-Risk Category — crypto exchange")
    void pattern_highRiskCategory_crypto() {
        FraudAssessment result = evaluate(
                txWithCategory("CUST_CRYPTO", "LUNO_EXCHANGE", new BigDecimal("500.00"),
                        "ZAR", "CRYPTO_EXCHANGE", BUSINESS_HOURS),
                List.of(), Set.of());

        assertThat(hasViolation(result, "HIGH_RISK_MERCHANT_CATEGORY"))
                .as("HIGH_RISK_MERCHANT_CATEGORY rule must fire for crypto exchange transactions")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
    }

    @Test @Order(80)
    @DisplayName("Pattern: Time-of-Day Anomaly — transaction at 03:00 UTC")
    void pattern_timeOfDayAnomaly() {
        FraudAssessment result = evaluate(
                tx("CUST_NIGHT", "SOME_SHOP", new BigDecimal("200.00"), "ZAR", OFF_HOURS),
                List.of(), Set.of());

        assertThat(hasViolation(result, "TIME_OF_DAY_ANOMALY"))
                .as("TIME_OF_DAY_ANOMALY rule must fire for transactions at 03:00 UTC")
                .isTrue();
        System.out.printf(
                "  [GAP NOTE] TIME_OF_DAY_ANOMALY fired alone — disposition: %s, riskScore: %d. "
                + "Needs a second corroborating signal to cross the fraud threshold.%n",
                result.getDisposition(), result.getRiskScore());
    }

    @Test @Order(81)
    @DisplayName("Pattern: Device Fingerprint — unrecognised device for customer")
    void pattern_deviceFingerprint() {
        Instant now = BUSINESS_HOURS;
        Transaction knownDevice = txWithFingerprint("CUST_DEVICE", "SHOP_KNOWN",
                new BigDecimal("40.00"), "ZAR", "DEV_KNOWN", now.minus(30, ChronoUnit.MINUTES));

        FraudAssessment result = evaluate(
                txWithFingerprint("CUST_DEVICE", "SHOP_NEW", new BigDecimal("40.00"), "ZAR",
                        "DEV_UNKNOWN", now),
                List.of(knownDevice), Set.of());

        assertThat(hasViolation(result, "DEVICE_FINGERPRINT"))
                .as("DEVICE_FINGERPRINT rule must fire for a device fingerprint never seen for this customer")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
    }

    @Test @Order(82)
    @DisplayName("Pattern: Cumulative Spending — hourly limit exceeded")
    void pattern_cumulativeSpending() {
        Instant now = BUSINESS_HOURS;
        List<Transaction> history = List.of(
                tx("CUST_SPEND", "SHOP_A", new BigDecimal("2500.00"), "ZAR", now.minus(15, ChronoUnit.MINUTES)),
                tx("CUST_SPEND", "SHOP_B", new BigDecimal("2500.00"), "ZAR", now.minus(30, ChronoUnit.MINUTES)),
                tx("CUST_SPEND", "SHOP_C", new BigDecimal("2500.00"), "ZAR", now.minus(45, ChronoUnit.MINUTES)));

        FraudAssessment result = evaluate(
                tx("CUST_SPEND", "SHOP_D", new BigDecimal("2600.00"), "ZAR", now),
                history, Set.of());

        assertThat(hasViolation(result, "CUMULATIVE_SPENDING"))
                .as("CUMULATIVE_SPENDING rule must fire when rolling hourly spend exceeds the limit")
                .isTrue();
        assertThat(result.getDisposition()).isEqualTo(Disposition.FLAGGED);
    }

    @Test @Order(83)
    @DisplayName("Pattern: Multi-Channel Anomaly — physical then online within 5 minutes")
    void pattern_multiChannelAnomaly() {
        Instant now = BUSINESS_HOURS;
        Transaction physical = txFull("CUST_CHANNEL", "STORE_PHYS", new BigDecimal("50.00"),
                "ZAR", null, null, TransactionType.CARD_PRESENT, now.minus(3, ChronoUnit.MINUTES));

        FraudAssessment result = evaluate(
                txFull("CUST_CHANNEL", "SHOP_ONLINE", new BigDecimal("80.00"), "ZAR",
                        null, null, TransactionType.CARD_NOT_PRESENT, now),
                List.of(physical), Set.of());

        assertThat(hasViolation(result, "MULTI_CHANNEL_ANOMALY"))
                .as("MULTI_CHANNEL_ANOMALY rule must fire for a physical->online channel switch within the window")
                .isTrue();
        System.out.printf(
                "  [GAP NOTE] MULTI_CHANNEL_ANOMALY fired alone — disposition: %s, riskScore: %d. "
                + "Needs a second corroborating signal to cross the fraud threshold.%n",
                result.getDisposition(), result.getRiskScore());
    }

    @Test @Order(84)
    @DisplayName("Pattern: Cross-Merchant Velocity — 10 transactions across merchants in 10 minutes")
    void pattern_crossMerchantVelocity() {
        Instant now = BUSINESS_HOURS;
        List<Transaction> history = buildHistory("CUST_XVEL", 9, 1, now);

        FraudAssessment result = evaluate(
                tx("CUST_XVEL", "SHOP_X", new BigDecimal("30.00"), "ZAR", now),
                history, Set.of());

        assertThat(hasViolation(result, "CROSS_MERCHANT_VELOCITY"))
                .as("CROSS_MERCHANT_VELOCITY rule must fire for 10 transactions across merchants within the window")
                .isTrue();
        // Not asserting the fraud verdict here on purpose: CROSS_MERCHANT_VELOCITY
        // (>=10 in 10 min) and VELOCITY (>=5 in 10 min) share the same default
        // window, so any transaction set meeting this rule's threshold has almost
        // certainly already tripped VELOCITY. CROSS_MERCHANT_VELOCITY's likelihood
        // ratio is deliberately modest so it corroborates rather than double-counts
        // that same underlying pattern.
    }

    @Test @Order(85)
    @DisplayName("Pattern: Customer Amount Anomaly — large deviation from personal baseline")
    void pattern_customerAmountAnomaly() {
        Instant now = BUSINESS_HOURS;
        // 10 prior transactions around 80 ZAR — this customer's normal spend
        List<Transaction> baseline = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            baseline.add(tx("CUST_BASELINE", "SHOP_" + i, new BigDecimal(75 + (i % 6) + ".00"), "ZAR",
                    now.minus((long) i, ChronoUnit.DAYS)));
        }

        // 800 ZAR is nowhere near the global 5000 AMOUNT_THRESHOLD, but is a huge
        // personal anomaly for a customer whose typical transaction is ~80 ZAR
        FraudAssessment result = evaluate(
                tx("CUST_BASELINE", "SHOP_NEW", new BigDecimal("800.00"), "ZAR", now),
                List.of(), Set.of(), baseline);

        assertThat(hasViolation(result, "CUSTOMER_AMOUNT_ANOMALY"))
                .as("CUSTOMER_AMOUNT_ANOMALY rule must fire for a transaction far beyond the customer's own baseline")
                .isTrue();
        assertThat(hasViolation(result, "AMOUNT_THRESHOLD"))
                .as("800 ZAR must not trip the unrelated global AMOUNT_THRESHOLD rule")
                .isFalse();
        System.out.printf(
                "  [GAP NOTE] CUSTOMER_AMOUNT_ANOMALY fired alone — disposition: %s, riskScore: %d. "
                + "Needs a second corroborating signal to cross the fraud threshold.%n",
                result.getDisposition(), result.getRiskScore());
    }

    // =========================================================================
    // 2. COMBINED-SIGNAL PATTERNS
    //    Two independently-weak signals raise the score, but a log-odds model
    //    doesn't treat "two weak coincidences" as equivalent to "one certain
    //    signal" — that conflation was the problem the old additive model had.
    //    These scenarios land in the PENDING_REVIEW band (tier 4) rather than
    //    being silently treated the same as a clean, zero-violation transaction —
    //    this is the three-way disposition feature actually working, not just a
    //    non-regression check.
    // =========================================================================

    @Test @Order(90)
    @DisplayName("Combined: Card cloning + off-hours — elevated to PENDING_REVIEW, not auto-flagged")
    void combined_cardCloningAndOffHours_pendingReview() {
        FraudAssessment result = evaluate(
                tx("CUST_COMBO", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", OFF_HOURS),
                List.of(
                        tx("CUST_COMBO", "AMAZON.COM", new BigDecimal("99.99"), "ZAR",
                                OFF_HOURS.minus(5, ChronoUnit.MINUTES)),
                        tx("CUST_COMBO", "EBAY.COM",   new BigDecimal("99.99"), "ZAR",
                                OFF_HOURS.minus(3, ChronoUnit.MINUTES))),
                Set.of());

        assertThat(hasViolation(result, "CARD_CLONING")).isTrue();
        assertThat(hasViolation(result, "TIME_OF_DAY_ANOMALY")).isTrue();
        assertThat(result.getRiskScore())
                .as("Two corroborating weak signals should score well above a clean transaction's baseline")
                .isGreaterThan(5);
        assertThat(result.getDisposition()).isEqualTo(Disposition.PENDING_REVIEW);
    }

    @Test @Order(91)
    @DisplayName("Combined: Multi-channel switch + off-hours — elevated to PENDING_REVIEW, not auto-flagged")
    void combined_multiChannelAndOffHours_pendingReview() {
        Transaction physical = txFull("CUST_CHANNEL_COMBO", "STORE_PHYS", new BigDecimal("50.00"),
                "ZAR", null, null, TransactionType.CARD_PRESENT, OFF_HOURS.minus(3, ChronoUnit.MINUTES));

        FraudAssessment result = evaluate(
                txFull("CUST_CHANNEL_COMBO", "SHOP_ONLINE", new BigDecimal("80.00"), "ZAR",
                        null, null, TransactionType.CARD_NOT_PRESENT, OFF_HOURS),
                List.of(physical), Set.of());

        assertThat(hasViolation(result, "MULTI_CHANNEL_ANOMALY")).isTrue();
        assertThat(hasViolation(result, "TIME_OF_DAY_ANOMALY")).isTrue();
        assertThat(result.getRiskScore()).isGreaterThan(5);
        assertThat(result.getDisposition()).isEqualTo(Disposition.PENDING_REVIEW);
    }

    @Test @Order(100)
    @DisplayName("Combined: Gambling + off-hours — elevated to PENDING_REVIEW, not auto-flagged")
    void combined_gamblingAndOffHours_pendingReview() {
        FraudAssessment result = evaluate(
                txWithCategory("CUST_GAMBLE", "BETWAY_APP", new BigDecimal("300.00"),
                        "ZAR", "GAMBLING", OFF_HOURS),
                List.of(), Set.of());

        assertThat(hasViolation(result, "HIGH_RISK_MERCHANT_CATEGORY")).isTrue();
        assertThat(hasViolation(result, "TIME_OF_DAY_ANOMALY")).isTrue();
        assertThat(result.getRiskScore()).isGreaterThan(5);
        assertThat(result.getDisposition()).isEqualTo(Disposition.PENDING_REVIEW);
    }

    // =========================================================================
    // 3. LEGITIMATE SCENARIO VALIDATION
    //    None of these should produce a fraud verdict. Hard assertions.
    // =========================================================================

    @Test @Order(200)
    @DisplayName("Legitimate: Weekend shopping — 3 varied purchases during business hours")
    void legitimate_weekendShopping() {
        FraudAssessment result = evaluate(
                tx("CUST_LEGIT_1", "WOOLWORTHS", new BigDecimal("350.00"), "ZAR", BUSINESS_HOURS),
                List.of(
                        tx("CUST_LEGIT_1", "PICK_N_PAY", new BigDecimal("280.00"), "ZAR",
                                BUSINESS_HOURS.minus(45, ChronoUnit.MINUTES)),
                        tx("CUST_LEGIT_1", "EDGARS",     new BigDecimal("620.00"), "ZAR",
                                BUSINESS_HOURS.minus(90, ChronoUnit.MINUTES))),
                Set.of());

        assertThat(result.getDisposition())
                .as("Legitimate weekend shopping with varied merchants/amounts must be CLEARED")
                .isEqualTo(Disposition.CLEARED);
    }

    @Test @Order(210)
    @DisplayName("Legitimate: Large purchase just under threshold (4999.99 ZAR)")
    void legitimate_largePurchaseUnderThreshold() {
        FraudAssessment result = evaluate(
                tx("CUST_LEGIT_2", "SAMSUNG_STORE", new BigDecimal("4999.99"), "ZAR", BUSINESS_HOURS),
                List.of(), Set.of());

        assertThat(result.getDisposition())
                .as("Purchase of exactly 4999.99 ZAR must NOT trigger AMOUNT_THRESHOLD")
                .isEqualTo(Disposition.CLEARED);
        assertThat(hasViolation(result, "AMOUNT_THRESHOLD")).isFalse();
    }

    @Test @Order(211)
    @DisplayName("Legitimate: Purchase within normal personal variation despite exceeding it slightly")
    void legitimate_withinPersonalVariation() {
        Instant now = BUSINESS_HOURS;
        // History: mean 2500, population stdDev exactly 400 — a customer with genuinely
        // variable spending habits, not a flat/uniform pattern. New amount (3500) is
        // 2.5 stdDev away, under the 3.0 multiplier.
        List<Transaction> baseline = List.of(
                tx("CUST_LEGIT_7", "SHOP_A", new BigDecimal("1900.00"), "ZAR", now.minus(5, ChronoUnit.DAYS)),
                tx("CUST_LEGIT_7", "SHOP_B", new BigDecimal("2300.00"), "ZAR", now.minus(10, ChronoUnit.DAYS)),
                tx("CUST_LEGIT_7", "SHOP_C", new BigDecimal("2500.00"), "ZAR", now.minus(15, ChronoUnit.DAYS)),
                tx("CUST_LEGIT_7", "SHOP_D", new BigDecimal("2700.00"), "ZAR", now.minus(20, ChronoUnit.DAYS)),
                tx("CUST_LEGIT_7", "SHOP_E", new BigDecimal("3100.00"), "ZAR", now.minus(25, ChronoUnit.DAYS)));

        FraudAssessment result = evaluate(
                tx("CUST_LEGIT_7", "SHOP_F", new BigDecimal("3500.00"), "ZAR", now),
                List.of(), Set.of(), baseline);

        assertThat(hasViolation(result, "CUSTOMER_AMOUNT_ANOMALY"))
                .as("A purchase within this customer's normal variability must NOT trigger CUSTOMER_AMOUNT_ANOMALY")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test @Order(220)
    @DisplayName("Legitimate: Monthly subscription — same merchant/amount, 30+ days apart")
    void legitimate_recurringSubscription() {
        Instant now = BUSINESS_HOURS;
        Transaction lastMonth = txFull("CUST_LEGIT_3", "NETFLIX", new BigDecimal("199.00"),
                "ZAR", null, null, TransactionType.CARD_NOT_PRESENT,
                now.minus(31, ChronoUnit.DAYS));

        FraudAssessment result = evaluate(
                txFull("CUST_LEGIT_3", "NETFLIX", new BigDecimal("199.00"),
                        "ZAR", null, null, TransactionType.CARD_NOT_PRESENT, now),
                List.of(lastMonth), Set.of());

        assertThat(hasViolation(result, "DUPLICATE_TRANSACTION"))
                .as("Monthly subscription charge (31 days apart) must NOT trigger DUPLICATE_TRANSACTION")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
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

        FraudAssessment result = evaluate(cpt, List.of(jhb), Set.of());

        assertThat(hasViolation(result, "GEOGRAPHIC_ANOMALY"))
                .as("JHB→CPT in 3 hours is realistic (1400 km / 3h ≈ 467 km/h) — must NOT trigger GEOGRAPHIC_ANOMALY")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test @Order(240)
    @DisplayName("Legitimate: 4 purchases in 15 minutes — below velocity threshold")
    void legitimate_fourPurchasesInFifteenMinutes() {
        Instant now = BUSINESS_HOURS;
        List<Transaction> history = buildHistory("CUST_LEGIT_5", 4, 3, now);

        FraudAssessment result = evaluate(
                tx("CUST_LEGIT_5", "SHOP_X", new BigDecimal("120.00"), "ZAR", now),
                history, Set.of());

        assertThat(hasViolation(result, "VELOCITY"))
                .as("4 prior transactions (below max of 5) must NOT trigger VELOCITY rule")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test @Order(250)
    @DisplayName("Legitimate: Different-currency duplicate — USD vs ZAR at same merchant (Gap 7 fix)")
    void legitimate_differentCurrencySameMerchantAmount() {
        Instant now = BUSINESS_HOURS;
        Transaction zarPayment = txFull("CUST_LEGIT_6", "INTERNATIONAL_SHOP",
                new BigDecimal("100.00"), "ZAR", null, null,
                TransactionType.CARD_NOT_PRESENT,
                now.minus(2, ChronoUnit.MINUTES));

        FraudAssessment result = evaluate(
                txFull("CUST_LEGIT_6", "INTERNATIONAL_SHOP",
                        new BigDecimal("100.00"), "USD", null, null,
                        TransactionType.CARD_NOT_PRESENT, now),
                List.of(zarPayment), Set.of());

        assertThat(hasViolation(result, "DUPLICATE_TRANSACTION"))
                .as("100 ZAR followed by 100 USD at the same merchant must NOT be a duplicate (Gap 7 fix)")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test @Order(260)
    @DisplayName("Legitimate: Recognised device — same fingerprint as prior transaction")
    void legitimate_recognisedDeviceFingerprint() {
        Instant now = BUSINESS_HOURS;
        Transaction prior = txWithFingerprint("CUST_DEVICE_OK", "SHOP_A",
                new BigDecimal("60.00"), "ZAR", "DEV_A", now.minus(20, ChronoUnit.MINUTES));

        FraudAssessment result = evaluate(
                txWithFingerprint("CUST_DEVICE_OK", "SHOP_B", new BigDecimal("75.00"), "ZAR",
                        "DEV_A", now),
                List.of(prior), Set.of());

        assertThat(hasViolation(result, "DEVICE_FINGERPRINT"))
                .as("Repeat use of the same device fingerprint must NOT trigger DEVICE_FINGERPRINT")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    @Test @Order(270)
    @DisplayName("Legitimate: Spend well under hourly and daily cumulative limits")
    void legitimate_spendUnderCumulativeLimits() {
        Instant now = BUSINESS_HOURS;
        List<Transaction> history = List.of(
                tx("CUST_SPEND_OK", "SHOP_A", new BigDecimal("500.00"), "ZAR", now.minus(15, ChronoUnit.MINUTES)),
                tx("CUST_SPEND_OK", "SHOP_B", new BigDecimal("500.00"), "ZAR", now.minus(30, ChronoUnit.MINUTES)));

        FraudAssessment result = evaluate(
                tx("CUST_SPEND_OK", "SHOP_C", new BigDecimal("500.00"), "ZAR", now),
                history, Set.of());

        assertThat(hasViolation(result, "CUMULATIVE_SPENDING"))
                .as("Modest hourly spend (1500 ZAR, well under the 10000 limit) must NOT trigger CUMULATIVE_SPENDING")
                .isFalse();
        assertThat(result.getDisposition()).isEqualTo(Disposition.CLEARED);
    }

    // =========================================================================
    // 4. EFFECTIVENESS METRICS SUMMARY
    //    Aggregates the full scenario set and prints accuracy metrics.
    // =========================================================================

    @Test @Order(999)
    @DisplayName("Effectiveness Metrics — sensitivity, specificity, precision, F1")
    void effectivenessMetrics() {
        // ---- Fraud scenarios (expected: fraudulent = true) ----
        // Only scenarios calibrated as standalone-sufficient belong here. Weak
        // signals (AMOUNT_THRESHOLD alone) and correlated-weak combos (card
        // cloning + off-hours) are covered above as GAP NOTE / elevated-band
        // demonstrations instead — asserting fraudulent=true for them would
        // just be re-litigating the additive-scoring bug this suite now guards
        // against.
        record FraudScenario(String name, Transaction txn, List<Transaction> history, Set<String> blacklist) {}

        Instant now = BUSINESS_HOURS;

        List<FraudScenario> fraudCases = List.of(
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

                new FraudScenario("Device Fingerprint - Unknown Device",
                        txWithFingerprint("M_DEV", "SHOP_NEW", new BigDecimal("40.00"), "ZAR",
                                "DEV_UNKNOWN", now),
                        List.of(txWithFingerprint("M_DEV", "SHOP_KNOWN", new BigDecimal("40.00"), "ZAR",
                                "DEV_KNOWN", now.minus(30, ChronoUnit.MINUTES))),
                        Set.of()),

                new FraudScenario("Cumulative Spending - Hourly Limit",
                        tx("M_SPEND", "SHOP_D", new BigDecimal("2600.00"), "ZAR", now),
                        List.of(
                                tx("M_SPEND", "SHOP_A", new BigDecimal("2500.00"), "ZAR", now.minus(15, ChronoUnit.MINUTES)),
                                tx("M_SPEND", "SHOP_B", new BigDecimal("2500.00"), "ZAR", now.minus(30, ChronoUnit.MINUTES)),
                                tx("M_SPEND", "SHOP_C", new BigDecimal("2500.00"), "ZAR", now.minus(45, ChronoUnit.MINUTES))),
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
                        buildHistory("L_VEL", 4, 1, now), Set.of()),

                new LegitScenario("Recognised Device Fingerprint",
                        txWithFingerprint("L_DEV", "SHOP_B", new BigDecimal("75.00"), "ZAR", "DEV_A", now),
                        List.of(txWithFingerprint("L_DEV", "SHOP_A", new BigDecimal("60.00"), "ZAR", "DEV_A",
                                now.minus(20, ChronoUnit.MINUTES))),
                        Set.of()),

                new LegitScenario("Spend Under Cumulative Limits",
                        tx("L_SPEND", "SHOP_C", new BigDecimal("500.00"), "ZAR", now),
                        List.of(
                                tx("L_SPEND", "SHOP_A", new BigDecimal("500.00"), "ZAR", now.minus(15, ChronoUnit.MINUTES)),
                                tx("L_SPEND", "SHOP_B", new BigDecimal("500.00"), "ZAR", now.minus(30, ChronoUnit.MINUTES))),
                        Set.of())
        );

        // ---- Evaluate and classify ----
        int tp = 0, fn = 0, tn = 0, fp = 0;
        Map<String, Boolean> fraudResults   = new LinkedHashMap<>();
        Map<String, Boolean> legitResults   = new LinkedHashMap<>();

        for (FraudScenario s : fraudCases) {
            boolean detected = evaluate(s.txn(), s.history(), s.blacklist()).getDisposition() == Disposition.FLAGGED;
            fraudResults.put(s.name(), detected);
            if (detected) tp++; else fn++;
        }
        for (LegitScenario s : legitCases) {
            boolean flagged = evaluate(s.txn(), s.history(), s.blacklist()).getDisposition() == Disposition.FLAGGED;
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
        patternCoverage.put("VELOCITY",                    fraudResults.get("Velocity Attack") != null
                && fraudResults.get("Velocity Attack"));
        patternCoverage.put("DUPLICATE_TRANSACTION",       fraudResults.get("Duplicate Charge (CNP)") != null
                && fraudResults.get("Duplicate Charge (CNP)"));
        patternCoverage.put("AMOUNT_THRESHOLD",            hasViolation(evaluate(
                tx("AT", "SHOP", new BigDecimal("7500.00"), "ZAR", now),
                List.of(), Set.of()), "AMOUNT_THRESHOLD"));
        patternCoverage.put("BLACKLISTED_MERCHANT",        fraudResults.get("Blacklisted Merchant") != null
                && fraudResults.get("Blacklisted Merchant"));
        patternCoverage.put("GEOGRAPHIC_ANOMALY",          fraudResults.get("Impossible Travel") != null
                && fraudResults.get("Impossible Travel"));
        patternCoverage.put("CARD_CLONING",                hasViolation(evaluate(
                tx("CC", "ALIEXPRESS.COM", new BigDecimal("99.99"), "ZAR", now),
                List.of(tx("CC", "AMAZON.COM",  new BigDecimal("99.99"), "ZAR", now.minus(5, ChronoUnit.MINUTES)),
                        tx("CC", "EBAY.COM",    new BigDecimal("99.99"), "ZAR", now.minus(3, ChronoUnit.MINUTES))),
                Set.of()), "CARD_CLONING"));
        patternCoverage.put("HIGH_RISK_MERCHANT_CATEGORY", fraudResults.get("Crypto Exchange") != null
                && fraudResults.get("Crypto Exchange"));
        patternCoverage.put("TIME_OF_DAY_ANOMALY",         hasViolation(evaluate(
                tx("TOD", "SHOP", new BigDecimal("200.00"), "ZAR", OFF_HOURS),
                List.of(), Set.of()), "TIME_OF_DAY_ANOMALY"));
        patternCoverage.put("DEVICE_FINGERPRINT",          fraudResults.get("Device Fingerprint - Unknown Device") != null
                && fraudResults.get("Device Fingerprint - Unknown Device"));
        patternCoverage.put("CUMULATIVE_SPENDING",         fraudResults.get("Cumulative Spending - Hourly Limit") != null
                && fraudResults.get("Cumulative Spending - Hourly Limit"));
        patternCoverage.put("MULTI_CHANNEL_ANOMALY",       hasViolation(evaluate(
                txFull("MCA", "SHOP_ONLINE", new BigDecimal("80.00"), "ZAR", null, null,
                        TransactionType.CARD_NOT_PRESENT, now),
                List.of(txFull("MCA", "STORE_PHYS", new BigDecimal("50.00"), "ZAR", null, null,
                        TransactionType.CARD_PRESENT, now.minus(3, ChronoUnit.MINUTES))),
                Set.of()), "MULTI_CHANNEL_ANOMALY"));
        patternCoverage.put("CROSS_MERCHANT_VELOCITY",     hasViolation(evaluate(
                tx("XVEL", "SHOP_X", new BigDecimal("30.00"), "ZAR", now),
                buildHistory("XVEL", 9, 1, now),
                Set.of()), "CROSS_MERCHANT_VELOCITY"));
        patternCoverage.put("CUSTOMER_AMOUNT_ANOMALY",     hasViolation(evaluate(
                tx("CAA", "SHOP_NEW", new BigDecimal("800.00"), "ZAR", now),
                List.of(), Set.of(),
                List.of(tx("CAA", "SHOP_1", new BigDecimal("76.00"), "ZAR", now.minus(1, ChronoUnit.DAYS)),
                        tx("CAA", "SHOP_2", new BigDecimal("77.00"), "ZAR", now.minus(2, ChronoUnit.DAYS)),
                        tx("CAA", "SHOP_3", new BigDecimal("78.00"), "ZAR", now.minus(3, ChronoUnit.DAYS)),
                        tx("CAA", "SHOP_4", new BigDecimal("79.00"), "ZAR", now.minus(4, ChronoUnit.DAYS)),
                        tx("CAA", "SHOP_5", new BigDecimal("80.00"), "ZAR", now.minus(5, ChronoUnit.DAYS)))),
                "CUSTOMER_AMOUNT_ANOMALY"));

        // ---- Print report ----
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  FRAUD ENGINE EFFECTIVENESS METRICS");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  Confusion matrix:  TP=%d  FP=%d  TN=%d  FN=%d%n", tp, fp, tn, fn);
        System.out.println();
        printMetric("Sensitivity (TPR)", sensitivity, 0.85, "%");
        printMetric("Specificity (TNR)", specificity, 0.95, "%");
        printMetric("Precision",          precision,  0.90, "%");
        printMetric("False Positive Rate",fpr,        0.05, "% (lower is better)");
        printMetric("False Negative Rate",fnr,        0.15, "% (lower is better)");
        printMetric("F1 Score",           f1,         0.80, "");
        System.out.println();
        System.out.println("  FRAUD PATTERN COVERAGE:");
        patternCoverage.forEach((pattern, covered) ->
                System.out.printf("    %-35s %s%n", pattern, covered ? "✓ COVERED" : "✗ GAP DETECTED"));
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
        assertThat(tp).as("All standalone-sufficient fraud scenarios must be detected").isEqualTo(fraudCases.size());
        assertThat(tn).as("All legitimate scenarios must pass without false positives").isEqualTo(legitCases.size());
        assertThat(sensitivity).as("Sensitivity must be at least 0.85").isGreaterThanOrEqualTo(0.85);
        assertThat(f1).as("F1 score must be at least 0.80").isGreaterThanOrEqualTo(0.80);
        assertThat(patternCoverage.get("CARD_CLONING")).as("CARD_CLONING rule must fire (pattern detected)").isTrue();
        assertThat(patternCoverage.get("TIME_OF_DAY_ANOMALY")).as("TIME_OF_DAY_ANOMALY rule must fire").isTrue();
        assertThat(patternCoverage.get("HIGH_RISK_MERCHANT_CATEGORY")).as("HIGH_RISK_MERCHANT_CATEGORY rule must fire").isTrue();
        assertThat(patternCoverage.get("AMOUNT_THRESHOLD")).as("AMOUNT_THRESHOLD rule must fire").isTrue();
        assertThat(patternCoverage.get("DEVICE_FINGERPRINT")).as("DEVICE_FINGERPRINT rule must fire").isTrue();
        assertThat(patternCoverage.get("CUMULATIVE_SPENDING")).as("CUMULATIVE_SPENDING rule must fire").isTrue();
        assertThat(patternCoverage.get("MULTI_CHANNEL_ANOMALY")).as("MULTI_CHANNEL_ANOMALY rule must fire").isTrue();
        assertThat(patternCoverage.get("CROSS_MERCHANT_VELOCITY")).as("CROSS_MERCHANT_VELOCITY rule must fire").isTrue();
        assertThat(patternCoverage.get("CUSTOMER_AMOUNT_ANOMALY")).as("CUSTOMER_AMOUNT_ANOMALY rule must fire").isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private FraudAssessment evaluate(Transaction txn, List<Transaction> history, Set<String> blacklist) {
        return evaluate(txn, history, blacklist, List.of());
    }

    private FraudAssessment evaluate(Transaction txn, List<Transaction> history, Set<String> blacklist,
                                      List<Transaction> baselineHistory) {
        EvaluationContext ctx = EvaluationContext.builder()
                .recentCustomerTransactions(history)
                .blacklistedMerchantIds(blacklist)
                .customerBaselineTransactions(baselineHistory)
                .build();
        when(mockContextBuilder.build(any())).thenReturn(ctx);

        return ruleEngine.evaluate(txn);
    }

    private boolean hasViolation(FraudAssessment assessment, String ruleName) {
        return assessment.getRuleViolations().stream()
                .map(RuleViolation::getRuleName)
                .anyMatch(ruleName::equals);
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

    private Transaction txWithFingerprint(String customerId, String merchantId, BigDecimal amount,
                                           String currency, String fingerprint, Instant ts) {
        return Transaction.builder()
                .id(UUID.randomUUID()).customerId(customerId).merchantId(merchantId)
                .amount(amount).currency(currency).deviceFingerprint(fingerprint).timestamp(ts).build();
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
}
