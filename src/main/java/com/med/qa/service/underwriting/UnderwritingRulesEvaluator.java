package com.med.qa.service.underwriting;

import com.med.qa.domain.entity.DeclaredConditionDO;
import com.med.qa.domain.enums.EligibilityVerdict;
import org.springframework.lang.Nullable;

import java.time.LocalDate;

/**
 * Deterministic, model-free evaluation of pre-existing-disease (PED) eligibility at claim
 * pre-authorization.
 *
 * <p>This class contains zero calls to any AI model. Every branch is a plain, testable conditional
 * over a declaration record and two dates, so the same inputs always produce the same verdict,
 * regardless of which model (if any) was involved earlier in the claim's journey. That property is
 * what makes a verdict defensible when a policyholder escalates to the grievance cell or the IRDAI
 * IGMS portal.</p>
 *
 * <h2>Why the checks are a gate, not a list</h2>
 * <p>The order below is load-bearing, not cosmetic. Non-disclosure is evaluated <em>before</em> the
 * waiting period and short-circuits it entirely, because an undeclared condition is not rescued by
 * elapsed time: a policyholder who never declared their hypertension is rejected on hospitalisation
 * even years after any waiting period would have completed. Evaluating these as independent,
 * equally-weighted rules would produce a {@code COVERED} verdict for exactly that case, which is the
 * opposite of how the insurer actually adjudicates it.</p>
 *
 * <h2>What this class deliberately does not decide</h2>
 * <p>Whether the claimed condition <em>is</em> PED-related at all is a medical determination made by
 * the insurer's doctor team, who review the health reports, consultations, prescriptions and
 * treatment history. That judgment arrives here as an already-made input ({@code pedRelated}); this
 * class never infers it. Encoding "should this have been declared?" in Java would be a clinical
 * judgment dressed up as business logic.</p>
 */
public final class UnderwritingRulesEvaluator {

    private UnderwritingRulesEvaluator() {
    }

    /**
     * Evaluates PED eligibility for one claimed condition.
     *
     * @param declared      the policyholder's declaration record for this condition, or {@code null}
     *                      when no declaration exists for it
     * @param conditionCode the claimed condition's code, must not be blank
     * @param claimDate     the date of the claim / hospitalisation, must not be {@code null}
     * @param pedRelated    whether the insurer's doctor team determined this claim to be related to
     *                      a pre-existing disease
     * @return the verdict, the rationale to show a reviewer, and the waiting-period end date where
     *         one applies
     * @throws IllegalArgumentException if {@code conditionCode} is blank or {@code claimDate} is
     *                                  {@code null}
     */
    public static EligibilityOutcome evaluate(
            @Nullable DeclaredConditionDO declared,
            String conditionCode,
            LocalDate claimDate,
            boolean pedRelated) {

        if (conditionCode == null || conditionCode.isBlank()) {
            throw new IllegalArgumentException("conditionCode must not be blank");
        }
        if (claimDate == null) {
            throw new IllegalArgumentException("claimDate must not be null");
        }

        // Gate 0 - PED rules simply do not apply to an unrelated new illness. A broken arm six
        // months into a diabetes waiting period is covered on the ordinary claim path.
        if (!pedRelated) {
            return new EligibilityOutcome(EligibilityVerdict.NOT_PED_RELATED, null,
                    "The claimed condition was not assessed as pre-existing-disease related, "
                            + "so PED waiting periods do not apply.");
        }

        // Gate 1 - non-disclosure short-circuits everything below it. Elapsed time never cures it.
        if (declared == null) {
            return new EligibilityOutcome(EligibilityVerdict.REJECTED_NON_DISCLOSURE, null,
                    "The claim was assessed as pre-existing-disease related, but condition '"
                            + conditionCode + "' was not declared when the policy was purchased. "
                            + "A waiting period is not applicable to an undeclared condition.");
        }

        // Gate 2 - a permanent exclusion never lapses, so no end date is reported.
        if (declared.isPermanentlyExcluded()) {
            return new EligibilityOutcome(EligibilityVerdict.PERMANENTLY_EXCLUDED, null,
                    "Condition '" + conditionCode + "' was declared but permanently excluded from "
                            + "cover at issuance, so it does not become claimable with elapsed time.");
        }

        // Gate 3/4 - the arithmetic itself. The period comes from the declaration record, which
        // carries what was published for that product and any waiver add-on applied at issuance.
        LocalDate waitingPeriodEndsOn = declared.waitingPeriodEndsOn();
        if (claimDate.isBefore(waitingPeriodEndsOn)) {
            return new EligibilityOutcome(EligibilityVerdict.WAITING_PERIOD_NOT_MET, waitingPeriodEndsOn,
                    "Condition '" + conditionCode + "' was declared on " + declared.getDeclaredOn()
                            + " with a " + declared.getWaitingPeriodMonths() + "-month waiting period, "
                            + "which completes on " + waitingPeriodEndsOn + ". The claim date "
                            + claimDate + " falls within the waiting period.");
        }

        return new EligibilityOutcome(EligibilityVerdict.COVERED, waitingPeriodEndsOn,
                "Condition '" + conditionCode + "' was declared on " + declared.getDeclaredOn()
                        + " and its " + declared.getWaitingPeriodMonths() + "-month waiting period "
                        + "completed on " + waitingPeriodEndsOn + ", on or before the claim date "
                        + claimDate + ".");
    }

    /**
     * The deterministic outcome of one PED eligibility evaluation.
     *
     * @param verdict             the verdict, decided entirely by this class and never by a model
     * @param waitingPeriodEndsOn the date the waiting period completes, or {@code null} where the
     *                            concept does not apply (not PED-related, non-disclosure, or a
     *                            permanent exclusion that never lapses)
     * @param rationale           a plain-language explanation suitable for a claims reviewer and for
     *                            the audit trail behind a grievance or IRDAI escalation
     */
    public record EligibilityOutcome(
            EligibilityVerdict verdict,
            @Nullable LocalDate waitingPeriodEndsOn,
            String rationale) {
    }
}
