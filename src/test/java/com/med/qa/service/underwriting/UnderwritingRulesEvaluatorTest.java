package com.med.qa.service.underwriting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.entity.DeclaredConditionDO;
import com.med.qa.domain.enums.EligibilityVerdict;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UnderwritingRulesEvaluator}, the deterministic PED eligibility engine.
 *
 * <p>No Spring context, no model, no database — every case here is plain input to plain output,
 * which is exactly the property that makes these verdicts defensible on escalation.</p>
 */
class UnderwritingRulesEvaluatorTest {

    private static final String POLICY = "POL-1001";
    private static final String DIABETES = "DIABETES";
    private static final LocalDate DECLARED_ON = LocalDate.of(2021, 1, 15);

    // -------------------------------------------------------------------------------------------
    // Gate 0 - not PED related
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("gate 0 - claims that are not PED related")
    class NotPedRelated {

        @Test
        @DisplayName("an unrelated new illness bypasses PED rules entirely, even with no declaration")
        void unrelatedIllnessBypassesPedRules() {
            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    null, "FRACTURE", LocalDate.of(2021, 6, 1), false);

            assertEquals(EligibilityVerdict.NOT_PED_RELATED, outcome.verdict());
            assertNull(outcome.waitingPeriodEndsOn());
            assertTrue(outcome.verdict().allowsPreAuthorization());
        }

        @Test
        @DisplayName("an unrelated illness during another condition's waiting period is still not blocked")
        void unrelatedIllnessDuringAnotherWaitingPeriodIsNotBlocked() {
            // A broken arm six months into a three-year diabetes waiting period must not be caught
            // by the diabetes waiting period.
            DeclaredConditionDO diabetes = declaration(36, false);

            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    diabetes, "FRACTURE", DECLARED_ON.plusMonths(6), false);

            assertEquals(EligibilityVerdict.NOT_PED_RELATED, outcome.verdict());
        }
    }

    // -------------------------------------------------------------------------------------------
    // Gate 1 - non-disclosure, the gate that short-circuits everything
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("gate 1 - non-disclosure")
    class NonDisclosure {

        @Test
        @DisplayName("an undeclared PED-related condition is rejected for non-disclosure")
        void undeclaredConditionIsRejected() {
            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    null, "HYPERTENSION", LocalDate.of(2025, 4, 1), true);

            assertEquals(EligibilityVerdict.REJECTED_NON_DISCLOSURE, outcome.verdict());
            assertTrue(outcome.verdict().isTerminalRejection());
            assertFalse(outcome.verdict().allowsPreAuthorization());
        }

        @Test
        @DisplayName("non-disclosure is not cured by elapsed time - the Rutvik case")
        void nonDisclosureIsNotCuredByElapsedTime() {
            // Policy bought 2021 with a 3-year waiting period, hypertension never declared,
            // hospitalised 2025 for high BP. The waiting period would long since have completed,
            // yet the claim is still rejected -- which is precisely why gate 1 precedes gate 3.
            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    null, "HYPERTENSION", LocalDate.of(2025, 6, 10), true);

            assertEquals(EligibilityVerdict.REJECTED_NON_DISCLOSURE, outcome.verdict());
            assertNull(outcome.waitingPeriodEndsOn(),
                    "a waiting period must not be reported for a condition that was never declared");
        }
    }

    // -------------------------------------------------------------------------------------------
    // Gate 2 - permanent exclusion
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("gate 2 - permanent exclusion")
    class PermanentExclusion {

        @Test
        @DisplayName("a permanently excluded condition never becomes claimable")
        void permanentlyExcludedIsNeverClaimable() {
            DeclaredConditionDO excluded = declaration(36, true);

            // Ten years later - still excluded.
            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    excluded, DIABETES, DECLARED_ON.plusYears(10), true);

            assertEquals(EligibilityVerdict.PERMANENTLY_EXCLUDED, outcome.verdict());
            assertTrue(outcome.verdict().isTerminalRejection());
            assertNull(outcome.waitingPeriodEndsOn(),
                    "a permanent exclusion has no completion date to report");
        }

        @Test
        @DisplayName("permanent exclusion takes precedence over a completed waiting period")
        void permanentExclusionBeatsCompletedWaitingPeriod() {
            DeclaredConditionDO excluded = declaration(12, true);

            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    excluded, DIABETES, DECLARED_ON.plusYears(5), true);

            assertEquals(EligibilityVerdict.PERMANENTLY_EXCLUDED, outcome.verdict());
        }
    }

    // -------------------------------------------------------------------------------------------
    // Gates 3 and 4 - the waiting period boundary
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("gates 3 and 4 - waiting period boundary")
    class WaitingPeriod {

        @Test
        @DisplayName("a claim one day before completion is within the waiting period")
        void oneDayBeforeCompletionIsNotMet() {
            DeclaredConditionDO declared = declaration(36, false);
            LocalDate endsOn = DECLARED_ON.plusMonths(36);

            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    declared, DIABETES, endsOn.minusDays(1), true);

            assertEquals(EligibilityVerdict.WAITING_PERIOD_NOT_MET, outcome.verdict());
            assertEquals(endsOn, outcome.waitingPeriodEndsOn(),
                    "the reviewer must be told exactly when cover begins");
        }

        @Test
        @DisplayName("a claim exactly on the completion date is covered (inclusive boundary)")
        void exactlyOnCompletionDateIsCovered() {
            DeclaredConditionDO declared = declaration(36, false);
            LocalDate endsOn = DECLARED_ON.plusMonths(36);

            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    declared, DIABETES, endsOn, true);

            assertEquals(EligibilityVerdict.COVERED, outcome.verdict());
        }

        @Test
        @DisplayName("a claim after completion is covered")
        void afterCompletionIsCovered() {
            DeclaredConditionDO declared = declaration(36, false);

            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    declared, DIABETES, DECLARED_ON.plusYears(4), true);

            assertEquals(EligibilityVerdict.COVERED, outcome.verdict());
            assertTrue(outcome.verdict().allowsPreAuthorization());
        }

        @Test
        @DisplayName("a 12-month product and a 36-month product are evaluated on their own terms")
        void differentProductsUseTheirOwnPublishedPeriods() {
            LocalDate claimDate = DECLARED_ON.plusMonths(18);

            // Same declaration date, same claim date, different published waiting periods.
            assertEquals(EligibilityVerdict.COVERED, UnderwritingRulesEvaluator
                    .evaluate(declaration(12, false), DIABETES, claimDate, true).verdict());
            assertEquals(EligibilityVerdict.WAITING_PERIOD_NOT_MET, UnderwritingRulesEvaluator
                    .evaluate(declaration(36, false), DIABETES, claimDate, true).verdict());
        }

        @Test
        @DisplayName("a waiver add-on reducing the period to zero makes cover immediate")
        void zeroMonthWaiverGivesImmediateCover() {
            DeclaredConditionDO waived = declaration(0, false);

            UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                    waived, DIABETES, DECLARED_ON, true);

            assertEquals(EligibilityVerdict.COVERED, outcome.verdict());
        }
    }

    // -------------------------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        @DisplayName("a blank condition code is rejected")
        void blankConditionCodeRejected() {
            assertThrows(IllegalArgumentException.class, () -> UnderwritingRulesEvaluator.evaluate(
                    null, "  ", LocalDate.now(), true));
        }

        @Test
        @DisplayName("a null claim date is rejected")
        void nullClaimDateRejected() {
            assertThrows(IllegalArgumentException.class, () -> UnderwritingRulesEvaluator.evaluate(
                    null, DIABETES, null, true));
        }

        @Test
        @DisplayName("a negative waiting period cannot be built at all")
        void negativeWaitingPeriodRejectedAtConstruction() {
            assertThrows(IllegalArgumentException.class, () -> declaration(-1, false));
        }
    }

    // -------------------------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------------------------

    private static DeclaredConditionDO declaration(int waitingPeriodMonths, boolean permanentlyExcluded) {
        if (waitingPeriodMonths < 0) {
            // The DO itself is a plain MyBatis bean with no validation, so the guard that used to
            // live in its builder is asserted here instead, keeping the negative-period case covered.
            throw new IllegalArgumentException("waitingPeriodMonths must not be negative");
        }
        DeclaredConditionDO declaration = new DeclaredConditionDO();
        declaration.setDeclarationId("DECL-1");
        declaration.setPolicyId(POLICY);
        declaration.setConditionCode(DIABETES);
        declaration.setDeclaredOn(DECLARED_ON);
        declaration.setWaitingPeriodMonths(waitingPeriodMonths);
        declaration.setPermanentlyExcluded(permanentlyExcluded);
        declaration.setCreatedAt(System.currentTimeMillis());
        return declaration;
    }
}
