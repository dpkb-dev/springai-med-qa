package com.med.qa.controller.dto;

import com.med.qa.domain.enums.EligibilityVerdict;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.time.LocalDate;

/**
 * The outcome of a pre-existing-disease eligibility check at claim pre-authorization.
 *
 * @param policyId            the policy that was evaluated
 * @param conditionCode       the condition that was evaluated
 * @param verdict             the deterministic verdict
 * @param waitingPeriodEndsOn the date the waiting period completes, or {@code null} where the
 *                            concept does not apply — not PED-related, non-disclosure, or a
 *                            permanent exclusion that never lapses
 * @param rationale           a plain-language explanation for the reviewer and the audit trail
 * @param terminalRejection   whether this verdict terminally blocks the claim
 * @param escalationGuidance  the policyholder's recourse when the verdict is terminal, or
 *                            {@code null} otherwise
 */
public record PolicyEligibilityResponse(
        String policyId,
        String conditionCode,
        EligibilityVerdict verdict,
        @Nullable LocalDate waitingPeriodEndsOn,
        String rationale,
        boolean terminalRejection,
        @Nullable @Schema(description = "recourse path when the verdict terminally blocks the claim")
        String escalationGuidance) {

    /**
     * The recourse offered on a terminal verdict. This is the real, regulated escalation path rather
     * than an internal review step invented by this application: the insurer's grievance cell first,
     * and the IRDAI's IGMS portal if the policyholder remains dissatisfied.
     */
    public static final String ESCALATION_GUIDANCE =
            "If you wish to contest this outcome, please contact the insurer's grievance cell. "
                    + "If you remain dissatisfied, the matter can be escalated to the IRDAI through "
                    + "its Integrated Grievance Management System (IGMS).";
}
