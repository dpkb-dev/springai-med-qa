package com.med.qa.domain.enums;

/**
 * Outcome of a pre-existing-disease (PED) eligibility evaluation at claim pre-authorization.
 *
 * <p>These verdicts are produced exclusively by
 * {@code com.med.qa.service.underwriting.UnderwritingRulesEvaluator}, in plain deterministic Java —
 * never by an AI model. The same declaration record, condition code and claim date always yield the
 * same verdict, which is what makes an outcome defensible in a grievance-cell or IRDAI escalation.</p>
 *
 * <h2>On the terminal verdicts</h2>
 * <p>{@link #REJECTED_NON_DISCLOSURE}, {@link #PERMANENTLY_EXCLUDED} and
 * {@link #WAITING_PERIOD_NOT_MET} are terminal for this claim. That is a deliberate, business-signed
 * decision rather than a technical convenience: by the time this evaluation runs, the insurer's
 * doctor team has already made the medical determination of whether the claimed condition is
 * PED-related. What remains is a mechanical check of the declaration record, so no further internal
 * review step is invented here. The policyholder's recourse against a terminal verdict is the
 * insurer's grievance cell and, failing that, the IRDAI IGMS portal.</p>
 *
 * <p>{@link #NEEDS_UNDERWRITER_REVIEW} is deliberately narrow: it is for genuinely ambiguous or
 * incomplete records, which is the kind of complicated scenario the underwriting team actually
 * handles. It is not a soft landing for routine rejections.</p>
 */

public enum EligibilityVerdict {

    /**
     * The claimed condition is not PED-related, so pre-existing-disease rules do not apply at all
     * and the claim continues down the ordinary assessment path.
     */
    NOT_PED_RELATED(0, false),

    /**
     * The condition is PED-related but was never declared when the policy was purchased. Completing
     * a waiting period is irrelevant in this case: an undeclared condition is a non-disclosure, and
     * the waiting period is never even evaluated.
     */
    REJECTED_NON_DISCLOSURE(1, true),

    /**
     * The condition was declared but was permanently excluded from cover at issuance. Unlike a
     * waiting period, this never lapses, so there is no future date at which the condition becomes
     * claimable.
     */
    PERMANENTLY_EXCLUDED(2, true),

    /**
     * The condition was properly declared, but the applicable waiting period — as published for that
     * product and adjusted by any waiver add-on on the policy — has not yet elapsed on the claim
     * date.
     */
    WAITING_PERIOD_NOT_MET(3, true),

    /**
     * The condition was declared and its waiting period has elapsed, so it is eligible to proceed to
     * pre-authorization under the policy's remaining terms.
     */
    COVERED(4, false),

    /**
     * The declaration record is missing, ambiguous, or the condition code is not recognised. Routed
     * to the underwriting team as a genuine edge case, never as a substitute for a clear verdict.
     */
    NEEDS_UNDERWRITER_REVIEW(5, false);

    private final int code;

    private final boolean terminalRejection;

    EligibilityVerdict(int code, boolean terminalRejection) {
        this.code = code;
        this.terminalRejection = terminalRejection;
    }

    /**
     * Returns the numeric code persisted in storage and carried in audit records.
     *
     * @return the numeric verdict code
     */
    public int getCode() {
        return code;
    }

    /**
     * Whether this verdict terminally blocks the claim, leaving the grievance cell and the IRDAI
     * IGMS portal as the policyholder's recourse.
     *
     * @return {@code true} for {@link #REJECTED_NON_DISCLOSURE}, {@link #PERMANENTLY_EXCLUDED} and
     *         {@link #WAITING_PERIOD_NOT_MET}
     */
    public boolean isTerminalRejection() {
        return terminalRejection;
    }

    /**
     * Whether the claim may proceed to pre-authorization on the strength of this verdict.
     *
     * @return {@code true} for {@link #COVERED} and {@link #NOT_PED_RELATED}
     */
    public boolean allowsPreAuthorization() {
        return this == COVERED || this == NOT_PED_RELATED;
    }

    /**
     * Resolves an {@link EligibilityVerdict} from its numeric code.
     *
     * @param code the numeric code (0-5) read from storage
     * @return the matching verdict
     * @throws IllegalArgumentException if the code maps to no known verdict
     */
    public static EligibilityVerdict fromCode(int code) {
        for (EligibilityVerdict verdict : values()) {
            if (verdict.code == code) {
                return verdict;
            }
        }
        throw new IllegalArgumentException("Unknown EligibilityVerdict code: " + code);
    }
}
