package com.med.qa.service.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapCalculator}, the deterministic, model-free arithmetic and verdict logic
 * behind the claim-eligibility feature.
 *
 * <p>Every test here runs with zero AI model calls, zero Spring context, and zero mocking — the
 * entire point of separating this logic out of {@link ClaimEligibilityService} is that it can be
 * exhaustively tested as plain Java, the same way any other business-rule class would be.</p>
 */
class CapCalculatorTest {

    private static final BigDecimal SUM_INSURED = new BigDecimal("300000");

    // ------------------------------------------------------------------
    // Boundary conditions: claimed amount <, ==, and > the calculated cap
    // ------------------------------------------------------------------

    @Test
    @DisplayName("claimed amount below the cap is LIKELY_COVERED")
    void belowCapIsCovered() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("1"), "1% per day");

        // 300000 @ 1%/day x 3 days = 9000.00 cap; claim below it
        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("8500"), 3);

        assertEquals("LIKELY_COVERED", outcome.verdict());
        assertEquals(new BigDecimal("9000.00"), outcome.calculatedCap());
    }

    @Test
    @DisplayName("claimed amount exactly equal to the cap is LIKELY_COVERED (inclusive boundary)")
    void exactlyAtCapIsCovered() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("1"), "1% per day");

        // 300000 @ 1%/day x 3 days = 9000.00 cap; claim exactly at it
        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("9000"), 3);

        assertEquals("LIKELY_COVERED", outcome.verdict());
        assertEquals(new BigDecimal("9000.00"), outcome.calculatedCap());
    }

    @Test
    @DisplayName("claimed amount above the cap is NEEDS_MANUAL_REVIEW, with the excess stated")
    void aboveCapNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("1"), "1% per day");

        // 300000 @ 1%/day x 3 days = 9000.00 cap; claim of 15000 exceeds it by 6000.00
        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("15000"), 3);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertEquals(new BigDecimal("9000.00"), outcome.calculatedCap());
        assertEquals("Calculated cap is 9000.00; claimed amount 15000 exceeds the cap by 6000.00.",
                outcome.calculationNote());
    }

    // ------------------------------------------------------------------
    // Non-capped applicability branches
    // ------------------------------------------------------------------

    @Test
    @DisplayName("EXCLUDED applicability is always LIKELY_EXCLUDED, regardless of any cap fields")
    void excludedIgnoresCapFields() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "EXCLUDED", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("50"), "not covered");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("1"), 1);

        assertEquals("LIKELY_EXCLUDED", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    @Test
    @DisplayName("COVERED_NO_LIMIT is LIKELY_COVERED with no calculated cap")
    void coveredNoLimitHasNoCap() {
        ClauseAnalysis analysis = new ClauseAnalysis("COVERED_NO_LIMIT", null, null, "fully covered");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("999999"), null);

        assertEquals("LIKELY_COVERED", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    @Test
    @DisplayName("UNCLEAR applicability needs manual review")
    void unclearNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis("UNCLEAR", null, null, "clause is ambiguous");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("100"), null);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
    }

    @Test
    @DisplayName("an unrecognized/malformed applicability value fails safe to NEEDS_MANUAL_REVIEW")
    void unrecognizedApplicabilityFailsSafe() {
        ClauseAnalysis analysis = new ClauseAnalysis("SOMETHING_UNEXPECTED", null, null, "n/a");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("100"), null);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
    }

    // ------------------------------------------------------------------
    // Missing-information branches: fail safe, never guess
    // ------------------------------------------------------------------

    @Test
    @DisplayName("COVERED_WITH_CAP with no capBasis/capValue extracted needs manual review")
    void cappedWithMissingBasisNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis("COVERED_WITH_CAP", null, null, "cap mentioned but unclear");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("100"), 1);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    @Test
    @DisplayName("a per-day cap with no day count supplied needs manual review, never guesses a day count")
    void perDayCapWithoutDaysNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("1"), "1% per day");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("1000"), null);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    // ------------------------------------------------------------------
    // The other two cap bases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PERCENT_OF_SUM_INSURED_FLAT calculates a one-time percentage cap correctly")
    void flatPercentageCap() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_FLAT", new BigDecimal("2"), "2% one-time cap");

        // 500000 @ 2% = 10000.00
        CapCalculator.CapOutcome outcome = CapCalculator.evaluate(
                analysis, new BigDecimal("500000"), new BigDecimal("10000"), null);

        assertEquals("LIKELY_COVERED", outcome.verdict());
        assertEquals(new BigDecimal("10000.00"), outcome.calculatedCap());
    }

    @Test
    @DisplayName("FLAT_AMOUNT uses the stated currency figure directly, ignoring sum insured entirely")
    void flatAmountCapIgnoresSumInsured() {
        ClauseAnalysis analysis =
                new ClauseAnalysis("COVERED_WITH_CAP", "FLAT_AMOUNT", new BigDecimal("5000"), "flat 5000 cap");

        CapCalculator.CapOutcome underCap = CapCalculator.evaluate(
                analysis, new BigDecimal("999999999"), new BigDecimal("5000"), null);
        assertEquals("LIKELY_COVERED", underCap.verdict());
        assertEquals(new BigDecimal("5000.00"), underCap.calculatedCap());

        CapCalculator.CapOutcome overCap = CapCalculator.evaluate(
                analysis, new BigDecimal("999999999"), new BigDecimal("5000.01"), null);
        assertEquals("NEEDS_MANUAL_REVIEW", overCap.verdict());
    }

    // ------------------------------------------------------------------
    // The business rounding rule itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("caps round DOWN (in the insurer's favor), not HALF_UP, matching ClaimCapPolicy")
    void roundingIsInInsurersFavor() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_FLAT", new BigDecimal("0.146"), "0.146% cap");

        // 333333 * 0.146% = 486.66618 (unrounded) -> DOWN gives 486.66, HALF_UP would give 486.67
        CapCalculator.CapOutcome outcome = CapCalculator.evaluate(
                analysis, new BigDecimal("333333"), new BigDecimal("486.66"), null);

        assertEquals(new BigDecimal("486.66"), outcome.calculatedCap());
    }

    // ------------------------------------------------------------------
    // Null applicability handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null applicability is treated as UNCLEAR and needs manual review")
    void nullApplicabilityNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis(null, null, null, "model returned null");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("100"), null);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    // ------------------------------------------------------------------
    // Partially missing cap information
    // ------------------------------------------------------------------

    @Test
    @DisplayName("COVERED_WITH_CAP with a capBasis but missing capValue needs manual review")
    void cappedWithMissingValueNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "FLAT_AMOUNT", null, "cap basis found but amount missing");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("100"), null);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    // ------------------------------------------------------------------
    // Unrecognized cap basis
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unrecognized capBasis fails safe to NEEDS_MANUAL_REVIEW with a descriptive note")
    void unrecognizedCapBasisFailsSafe() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_PREMIUM", new BigDecimal("10"), "hallucinated basis");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("100"), null);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
        assertEquals("Cannot calculate the applicable cap because the cap basis reported was not one this calculator recognizes ('PERCENT_OF_PREMIUM'); requires manual review.",
                outcome.calculationNote());
    }

    // ------------------------------------------------------------------
    // Invalid day counts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a per-day cap with zero days needs manual review, never calculates a zero cap")
    void perDayCapWithZeroDaysNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("1"), "1% per day");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("1000"), 0);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }

    @Test
    @DisplayName("a per-day cap with negative days needs manual review")
    void perDayCapWithNegativeDaysNeedsReview() {
        ClauseAnalysis analysis = new ClauseAnalysis(
                "COVERED_WITH_CAP", "PERCENT_OF_SUM_INSURED_PER_DAY", new BigDecimal("1"), "1% per day");

        CapCalculator.CapOutcome outcome =
                CapCalculator.evaluate(analysis, SUM_INSURED, new BigDecimal("1000"), -3);

        assertEquals("NEEDS_MANUAL_REVIEW", outcome.verdict());
        assertNull(outcome.calculatedCap());
    }
}
