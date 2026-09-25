package com.med.qa.service.claims;

import java.math.RoundingMode;

/**
 * Centralizes the deterministic business rules used to turn a {@link ClauseAnalysis} into an actual
 * monetary cap.
 *
 * <p>These are explicit business decisions, not engineering defaults — they directly affect payout
 * amounts and should be reviewed and owned by the business/actuarial team, not silently changed by a
 * developer. Different insurers reasonably make different choices here (e.g. rounding in the
 * insurer's favor vs. the policyholder's); this class exists specifically so that choice is a single,
 * visible, documented line, not something buried inside a calculation.</p>
 */
final class ClaimCapPolicy {

    /**
     * Caps computed from a percentage-of-sum-insured clause are rounded DOWN to the nearest currency
     * unit — i.e. in the insurer's favor. Change only with explicit business/actuarial sign-off.
     */
    static final RoundingMode CAP_ROUNDING = RoundingMode.DOWN;

    /** Decimal scale (digits after the decimal point) used for every calculated cap. */
    static final int CAP_SCALE = 2;

    private ClaimCapPolicy() {
    }
}
