package com.med.qa.domain.enums;

/**
 * How a claim reaches the insurer, which determines when in the patient's journey the claim
 * assessment runs.
 *
 * <p>The assessment logic itself is identical for both: the same eligibility rules, the same
 * document consistency checks and the same line-item caps apply. What differs is the entry point and
 * the time pressure, which is why the two are distinguished on the request rather than inferred.</p>
 */
public enum ClaimType {

    /**
     * Raised while the patient is admitted. The hospital's TPA desk fills a pre-authorization form
     * and sends it to the insurer, whose doctor team reviews it and sanctions an initial amount
     * before or during treatment. Time-critical: a patient is in a hospital bed awaiting the answer.
     */
    CASHLESS(0),

    /**
     * Raised after discharge, once the policyholder has paid out of pocket. The insurer is typically
     * notified within 48 hours of admission, and the supporting documents are submitted within 30
     * days of discharge. Less time-critical than cashless, but the same assessment applies.
     */
    REIMBURSEMENT(1);

    private final int code;

    ClaimType(int code) {
        this.code = code;
    }

    /**
     * @return the numeric code persisted in storage and carried in audit records
     */
    public int getCode() {
        return code;
    }

    /**
     * Resolves a {@link ClaimType} from its numeric code.
     *
     * @param code the numeric code read from storage
     * @return the matching claim type
     * @throws IllegalArgumentException if the code maps to no known type
     */
    public static ClaimType fromCode(int code) {
        for (ClaimType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown ClaimType code: " + code);
    }
}
