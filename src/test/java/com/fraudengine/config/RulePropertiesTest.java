package com.fraudengine.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Guards RuleProperties.validate()'s startup fail-fast check: a rule window wider than
// context-lookback-minutes must never silently under-count (recentCustomerTransactions
// simply wouldn't hold the data). Previously untested entirely — the duplicate-window gap
// found live 2026-09-16 (its two seconds-based windows were missing from this check) shipped
// undetected because nothing exercised validate() at all, in either direction.
class RulePropertiesTest {

    @Test
    void defaultConfig_doesNotThrow() {
        assertThatCode(() -> new RuleProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void windowExactlyAtLookback_doesNotThrow() {
        RuleProperties props = new RuleProperties();
        props.setContextLookbackMinutes(60);
        props.getVelocity().setWindowMinutes(60);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void minutesWindowExceedsLookback_throws() {
        RuleProperties props = new RuleProperties();
        props.setContextLookbackMinutes(60);
        props.getVelocity().setWindowMinutes(61);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("velocity")
                .hasMessageContaining("61")
                .hasMessageContaining("60");
    }

    @Test
    void anotherMinutesWindowExceedsLookback_throws() {
        // Cross-checks a second entry in the map, not just velocity, since the map's
        // correctness (every window-based rule actually listed) is the point of this class.
        // Lookback raised well above every OTHER rule's default (max default is 60) so this
        // assertion isolates to the one field actually under test.
        RuleProperties props = new RuleProperties();
        props.setContextLookbackMinutes(100);
        props.getCumulativeSpending().setHourlyWindowMinutes(101);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cumulative-spending.hourly-window");
    }

    @Test
    void duplicateCardPresentWindowSecondsExceedsLookback_throws() {
        // The exact gap found live 2026-09-16: duplicate's windows are seconds-based and were
        // missing from the original minutes-only map entirely, so an out-of-range value here
        // used to start up successfully instead of failing fast like every sibling rule.
        // Lookback left at the default (60 min = 3600s): every other rule's default minutes
        // window already fits under it, isolating this assertion to the duplicate check alone.
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(3601);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate.card-present-window-seconds");
    }

    @Test
    void duplicateCardNotPresentWindowSecondsExceedsLookback_throws() {
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardNotPresentWindowSeconds(3601);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate.card-not-present-window-seconds");
    }

    @Test
    void duplicateWindowSecondsExactlyAtLookback_doesNotThrow() {
        RuleProperties props = new RuleProperties();
        props.getDuplicate().setCardPresentWindowSeconds(3600);
        props.getDuplicate().setCardNotPresentWindowSeconds(3600);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }
}
